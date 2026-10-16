package ru.altar;

import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Rotatable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Мрачный декор вокруг постройки: выжженная земля, шипы, черепа, столбы с огнём душ. Рисуется ТОЛЬКО вне схематики. */
final class Decor {

    private Decor() { }

    /** @return верхние точки столбов (для частиц) */
    static List<Block> apply(World w, int minX, int minZ, int sx, int sz, int baseY, int margin, Random r) {
        int maxX = minX + sx - 1, maxZ = minZ + sz - 1;

        for (int x = minX - margin; x <= maxX + margin; x++) {
            for (int z = minZ - margin; z <= maxZ + margin; z++) {
                int dx = Math.max(Math.max(minX - x, x - maxX), 0);
                int dz = Math.max(Math.max(minZ - z, z - maxZ), 0);
                int d = Math.max(dx, dz);
                if (d == 0) continue;
                if (r.nextDouble() > 0.95 - d * 0.065) continue;

                int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                Block top = w.getBlockAt(x, y, z);
                if (top.isLiquid() || top.getType().isAir()) continue;
                Block above = top.getRelative(BlockFace.UP);
                boolean free = above.getType().isAir();

                Material m = ground(r);
                top.setType(m, false);
                if (!free) continue;

                double p = r.nextDouble();
                switch (m) {
                    case SOUL_SOIL -> { if (p < 0.22) above.setType(Material.SOUL_FIRE, false); }
                    case BLACKSTONE -> {
                        if (p < 0.05) skull(above, r);
                        else if (p < 0.05 + (d >= 2 ? 0.035 : 0)) spike(above, r);
                    }
                    case CRIMSON_NYLIUM -> {
                        if (p < 0.20) above.setType(Material.CRIMSON_ROOTS, false);
                        else if (p < 0.27) above.setType(Material.CRIMSON_FUNGUS, false);
                    }
                    case COBBLED_DEEPSLATE -> { if (p < 0.03) above.setType(Material.COBWEB, false); }
                    default -> { }
                }
            }
        }

        List<Block> tops = new ArrayList<>();
        int[][] corners = {{minX - 4, minZ - 4}, {maxX + 4, minZ - 4}, {minX - 4, maxZ + 4}, {maxX + 4, maxZ + 4}};
        for (int[] c : corners) tops.add(pillar(w, c[0], c[1], r));
        return tops;
    }

    private static Material ground(Random r) {
        int v = r.nextInt(100);
        if (v < 35) return Material.BLACKSTONE;
        if (v < 55) return Material.SOUL_SOIL;
        if (v < 70) return Material.COBBLED_DEEPSLATE;
        if (v < 80) return Material.BASALT;
        if (v < 90) return Material.CRIMSON_NYLIUM;
        if (v < 96) return Material.NETHERRACK;
        return Material.CRYING_OBSIDIAN;
    }

    private static void skull(Block b, Random r) {
        b.setType(Material.WITHER_SKELETON_SKULL, false);
        BlockData bd = b.getBlockData();
        if (bd instanceof Rotatable rot) {
            BlockFace[] faces = BlockFace.values();
            BlockFace f;
            do { f = faces[r.nextInt(faces.length)]; } while (!isRot(f));
            rot.setRotation(f);
            b.setBlockData(rot, false);
        }
    }

    private static boolean isRot(BlockFace f) {
        return switch (f) {
            case NORTH, NORTH_NORTH_EAST, NORTH_EAST, EAST_NORTH_EAST, EAST, EAST_SOUTH_EAST, SOUTH_EAST, SOUTH_SOUTH_EAST,
                 SOUTH, SOUTH_SOUTH_WEST, SOUTH_WEST, WEST_SOUTH_WEST, WEST, WEST_NORTH_WEST, NORTH_WEST, NORTH_NORTH_WEST -> true;
            default -> false;
        };
    }

    private static void spike(Block b, Random r) {
        int h = 2 + r.nextInt(3);
        for (int i = 0; i < h; i++) {
            Block t = b.getRelative(0, i, 0);
            if (!t.getType().isAir()) break;
            t.setType(i == h - 1 ? Material.POLISHED_BLACKSTONE_BRICK_WALL : Material.BLACKSTONE_WALL, false);
        }
    }

    private static Block pillar(World w, int x, int z, Random r) {
        int g = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        int y0 = g + 1;
        // фундамент 3x3 + подсыпка вниз
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int yy = y0 - 1; yy > y0 - 9 && yy > w.getMinHeight(); yy--) {
                    Block b = w.getBlockAt(x + dx, yy, z + dz);
                    if (b.getType().isSolid()) break;
                    b.setType(Material.BLACKSTONE, false);
                }
                w.getBlockAt(x + dx, y0, z + dz).setType(Material.POLISHED_BLACKSTONE_BRICKS, false);
            }
        }
        int h = 7 + r.nextInt(3);
        for (int i = 1; i <= h; i++) {
            Material m = (i % 3 == 0) ? Material.CHISELED_POLISHED_BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS;
            if (i == 1) m = Material.GILDED_BLACKSTONE;
            w.getBlockAt(x, y0 + i, z).setType(m, false);
        }
        Block top = w.getBlockAt(x, y0 + h + 1, z);
        top.setType(Material.SOUL_CAMPFIRE, false);
        w.getBlockAt(x, y0 + h, z).setType(Material.CHISELED_POLISHED_BLACKSTONE, false);
        return top;
    }
}
