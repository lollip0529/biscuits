package io.github.lollip0529.biscuits;

import io.github.lollip0529.biscuits.Snapshot.Section;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.chunk.ChunkOcclusionData;
import net.minecraft.client.render.chunk.ChunkOcclusionDataBuilder;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.List;

// why a section is or isn't reached, grouped like stronghold-culling-explainer's culling.py. full view search first,
// then each fix on its own. fixes can only detour 2 sections outside the box between start and target
public class Route {
    // explainer's names
    public enum Group {
        REACHED("Not culled"),
        VIEW("View too narrow"),
        FIRST_ENTRY("First entry"),
        FIRST_ENTRY_VERTICAL("First entry, a vertical fix also works"),
        FIRST_ENTRY_SIDEWAYS("First entry, a sideways fix also works"),
        FIRST_ENTRY_BOTH("First entry, lifting both rules also works"),
        DOWN_UP("Vertical, down then up"),
        UP_DOWN("Vertical, up then down"),
        EITHER_DOWN_UP("Either rule, down then up"),
        EITHER_UP_DOWN("Either rule, up then down"),
        HORIZONTAL("Horizontal"),
        BOTH("Needs both rules"),
        FIRST_ENTRY_RULE("First entry + direction rule"),
        NO_ROUTE("No local route");

        public final String label;

        Group(String label) {
            this.label = label;
        }
    }

    private static final int ALL_BANS = 0x3F, VERTICAL_BANS = 0x03, HORIZONTAL_BANS = 0x3C;
    private static final int MARGIN = 2;

    public final Section target;
    private final Section root;
    // not from the camera (spectator / starter), so no frustum and no live search behind it
    public final boolean replayed;
    public Group group;
    private boolean reentry, vertical, sideways, both;

    private final Snapshot snap;
    private final Long2LongMap fresh = new Long2LongOpenHashMap();
    private Long2ObjectMap<Node> reached;

    private static final class Node {
        final Section sec;
        final Direction flow;
        final int bans;
        final Node parent;
        final int order;

        Node(Section sec, Direction flow, int bans, Node parent, int order) {
            this.sec = sec;
            this.flow = flow;
            this.bans = bans;
            this.parent = parent;
            this.order = order;
        }
    }

    private Route(Snapshot snap, Section root, Section target, boolean replayed) {
        this.snap = snap;
        this.root = root;
        this.target = target;
        this.replayed = replayed;
    }

    // sodium's own search from the camera, null if the camera isn't in a loaded section
    static Route of(Snapshot snap, Section target) {
        Section root = snap.get(snap.centerX, snap.centerY, snap.centerZ);
        if (root == null || !root.visited()) return null;
        Route r = new Route(snap, root, target, false);
        if (!r.inRange(target)) return null;
        r.reached = new Long2ObjectOpenHashMap<>();
        for (Section sec : snap.sections.values()) {
            if (sec.visited()) r.reached.put(key(sec), new Node(sec, sec.flow, sec.bans, null, sec.order));
        }
        r.classify();
        return r;
    }

    // replay from root with a full view, like the camera was standing there
    public static Route from(Snapshot snap, Section target, Section root) {
        Route r = new Route(snap, root, target, true);
        if (!r.inRange(target)) return null;
        r.reached = r.search(ALL_BANS, null, true);
        r.classify();
        return r;
    }

    public boolean reached(Section sec) {
        return this.reached.containsKey(key(sec));
    }

    public int order(Section sec) {
        Node n = this.reached.get(key(sec));
        return n == null ? -1 : n.order;
    }

    public byte bans(Section sec) {
        Node n = this.reached.get(key(sec));
        return n == null ? 0 : (byte) n.bans;
    }

    public String fixes() {
        if (this.group == Group.REACHED || this.group == Group.VIEW) return null;
        return "re-entry " + yes(this.reentry) + ", vertical " + yes(this.vertical) + ", sideways " + yes(this.sideways) + ", both " + yes(this.both);
    }

    // why the search never went from a into b, same checks as ChunkGraphCuller.computeVisible / bfsEnqueue
    public String why(Section a, Section b) {
        Node n = this.reached.get(key(a));
        Direction d = Direction.fromVector(b.x - a.x, b.y - a.y, b.z - a.z);
        if ((n.bans & 1 << d.ordinal()) != 0) return "can't go " + d.getName() + ", already went " + d.getOpposite().getName();
        if (this.snap.occlusion && n.flow != null && !this.canSee(a, n.flow, d, this.replayed)) {
            return "no air from its " + n.flow.getName() + " side to its " + d.getName() + " side";
        }
        if (!this.inRange(b)) return "past the render distance";
        if (!this.replayed && !b.inFrustum) return "off screen";
        return "open, changed since the search";
    }

    private void classify() {
        if (this.reached(this.target)) {
            this.group = Group.REACHED;
            return;
        }
        if (!this.replayed && this.search(ALL_BANS, null, false).containsKey(key(this.target))) {
            this.group = Group.VIEW;
            return;
        }

        int[] strict = new int[6], local = new int[6];
        for (int i = 0; i < 3; i++) {
            int a = coord(this.root, i), b = coord(this.target, i);
            strict[i] = local[i] = Math.min(a, b);
            strict[i + 3] = local[i + 3] = Math.max(a, b);
            local[i] -= MARGIN;
            local[i + 3] += MARGIN;
        }
        // vertical detours stay in the strict box sideways, sideways ones stay in the strict layers
        int[] vbox = {strict[0], local[1], strict[2], strict[3], local[4], strict[5]};
        int[] hbox = {local[0], strict[1], local[2], local[3], strict[4], local[5]};

        Node vt = this.search(HORIZONTAL_BANS, vbox, true).get(key(this.target));
        this.reentry = this.reentry(local, true);
        this.vertical = vt != null;
        this.sideways = this.search(VERTICAL_BANS, hbox, true).containsKey(key(this.target));
        this.both = this.search(0, local, true).containsKey(key(this.target));

        if (this.reentry) {
            this.group = this.vertical ? Group.FIRST_ENTRY_VERTICAL : this.sideways ? Group.FIRST_ENTRY_SIDEWAYS : this.both ? Group.FIRST_ENTRY_BOTH : Group.FIRST_ENTRY;
        } else if (vt != null) {
            // the last layer change on the way in decides it. climbing back up into the target's layer = down then up
            boolean up = true;
            for (Node n = vt; n.parent != null; n = n.parent) {
                if (n.sec.y != n.parent.sec.y) {
                    up = n.sec.y > n.parent.sec.y;
                    break;
                }
            }
            this.group = this.sideways ? (up ? Group.EITHER_DOWN_UP : Group.EITHER_UP_DOWN) : (up ? Group.DOWN_UP : Group.UP_DOWN);
        } else if (this.sideways) {
            this.group = Group.HORIZONTAL;
        } else if (this.both) {
            this.group = Group.BOTH;
        } else {
            this.group = this.reentry(local, false) ? Group.FIRST_ENTRY_RULE : Group.NO_ROUTE;
        }
    }

    // ChunkGraphCuller's search minus the frustum test. rules = bans that still apply
    private Long2ObjectMap<Node> search(int rules, int[] box, boolean blocks) {
        Long2ObjectMap<Node> seen = new Long2ObjectOpenHashMap<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        Node start = new Node(this.root, null, 0, null, 0);
        seen.put(key(this.root), start);
        queue.add(start);
        while (!queue.isEmpty()) {
            Node n = queue.poll();
            for (Direction dir : Direction.values()) {
                Section adj = this.step(n, dir, rules, box, blocks);
                if (adj == null || seen.containsKey(key(adj))) continue;
                Direction flow = dir.getOpposite();
                Node next = new Node(adj, flow, n.bans | 1 << flow.ordinal(), n, seen.size());
                seen.put(key(adj), next);
                queue.add(next);
            }
        }
        return seen;
    }

    // same search but a section can be entered again through another face. rules off -> no bans at all
    private boolean reentry(int[] box, boolean rules) {
        LongSet seen = new LongOpenHashSet();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(new Node(this.root, null, 0, null, 0));
        while (!queue.isEmpty()) {
            Node n = queue.poll();
            for (Direction dir : Direction.values()) {
                Section adj = this.step(n, dir, rules ? ALL_BANS : 0, box, true);
                if (adj == null || adj == this.root) continue;
                Direction flow = dir.getOpposite();
                Node next = new Node(adj, flow, rules ? n.bans | 1 << flow.ordinal() : 0, n, 0);
                if (!seen.add(state(box, next))) continue;
                if (adj == this.target) return true;
                queue.add(next);
            }
        }
        return false;
    }

    private Section step(Node n, Direction dir, int rules, int[] box, boolean blocks) {
        if ((n.bans & rules & 1 << dir.ordinal()) != 0) return null;
        if (this.snap.occlusion && n.flow != null && !this.canSee(n.sec, n.flow, dir, blocks)) return null;
        Section adj = this.snap.get(n.sec.x + dir.getOffsetX(), n.sec.y + dir.getOffsetY(), n.sec.z + dir.getOffsetZ());
        if (adj == null || !this.inRange(adj)) return null;
        if (box != null && (adj.x < box[0] || adj.y < box[1] || adj.z < box[2] || adj.x > box[3] || adj.y > box[4] || adj.z > box[5])) return null;
        return adj;
    }

    private boolean inRange(Section sec) {
        return Math.abs(sec.x - this.root.x) <= this.snap.renderDistance && Math.abs(sec.z - this.root.z) <= this.snap.renderDistance;
    }

    private boolean canSee(Section sec, Direction from, Direction to, boolean blocks) {
        // sodium treats an unbuilt section as open on every side and keeps stale sight lines until it remeshes, so
        // those get flood filled from the real blocks
        if (!blocks || sec.built && !sec.rebuild) return sec.canSee(from, to);
        long through = this.fresh.computeIfAbsent(key(sec), k -> occlusion(sec));
        return (through & 1L << ((from.ordinal() << 3) + to.ordinal())) != 0L;
    }

    // what ChunkRenderRebuildTask would come up with - opaque full cubes block, the rest is flood filled
    private static long occlusion(Section sec) {
        World world = MinecraftClient.getInstance().world;
        ChunkOcclusionDataBuilder builder = new ChunkOcclusionDataBuilder();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    pos.set((sec.x << 4) + x, (sec.y << 4) + y, (sec.z << 4) + z);
                    if (world.getBlockState(pos).isOpaqueFullCube(world, pos)) builder.markClosed(pos);
                }
            }
        }
        ChunkOcclusionData data = builder.build();
        long through = 0;
        for (Direction from : Direction.values()) {
            for (Direction to : Direction.values()) {
                if (data.isVisibleThrough(from, to)) through |= 1L << ((from.ordinal() << 3) + to.ordinal());
            }
        }
        return through;
    }

    public int stale(List<Section> along) {
        int n = 0;
        for (Section sec : along) if (this.reached(sec) && (!sec.built || sec.rebuild)) n++;
        return n;
    }

    // section + entry face + bans in one long, for the seen set
    private static long state(int[] box, Node n) {
        long i = ((long) (n.sec.x - box[0]) * (box[4] - box[1] + 1) + (n.sec.y - box[1])) * (box[5] - box[2] + 1) + (n.sec.z - box[2]);
        return i << 9 | (n.flow == null ? 6 : n.flow.ordinal()) << 6 | n.bans;
    }

    private static long key(Section sec) {
        return ChunkSectionPos.asLong(sec.x, sec.y, sec.z);
    }

    private static int coord(Section sec, int axis) {
        return axis == 0 ? sec.x : axis == 1 ? sec.y : sec.z;
    }

    private static String yes(boolean b) {
        return b ? "yes" : "no";
    }
}
