import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.block.Block;

public class MapColors {
    public static void main(String[] a) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        StringBuilder sb = new StringBuilder("{\"blocks\":{");
        boolean first = true;
        for (Block b : BuiltInRegistries.BLOCK) {
            String id = BuiltInRegistries.BLOCK.getKey(b).toString();
            int c = b.defaultMapColor().id;
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(id).append("\":").append(c);
        }
        sb.append("},\"colors\":[");
        for (int i = 0; i < 64; i++) {
            if (i > 0) sb.append(',');
            sb.append(MapColor.byId(i).col);
        }
        sb.append("]}");
        java.nio.file.Files.writeString(java.nio.file.Path.of("mapcolors.json"), sb.toString());
        System.out.println("ok");
    }
}
