# biscuits

A full-screen map for Minecraft 1.16.1 (Fabric, MCSR Sodium 2.5.1) that shows the state of every loaded chunk section in Sodium's culling graph, in the style of the `chunk_section_status` debug view from newer snapshots. Based on [pastry](https://github.com/tesselslate/pastry) and [StrongholdTrainer](https://github.com/mjtb49/StrongholdTrainer)

## Colors

| Color | Meaning |
| --- | --- |
| light green | not culled, in FOV: Sodium's search reached it, so it is drawn if it has anything to draw |
| dark green | not culled, outside FOV: the search would reach it with its frustum test off; only the view angle keeps it out |
| gold | culled, in FOV: inside the frustum, but the search never reached it |
| dark gray | culled, outside FOV: unreachable even with the frustum test off |
| purple | loaded, but outside the render-distance square |
| dark red | not loaded |

## Controls

Mouse:

| Input | Action |
| --- | --- |
| scroll | zoom |
| ctrl + scroll (also shift + scroll, or scrolling over the slider) | change the section y shown |
| right click | pin a column and open the details panel; click the strip in the panel to jump to that section |

  Contact lollip0529 on discord if there's any issues. 