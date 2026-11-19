package cn.net.rms.confluxmap.neoforge.compat;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
public interface LevelRenderContext {
    default PoseStack poseStack() { return null; }
    default SubmitNodeCollector submitNodeCollector() { return null; }
    default LevelRenderState levelState() { return null; }
}
