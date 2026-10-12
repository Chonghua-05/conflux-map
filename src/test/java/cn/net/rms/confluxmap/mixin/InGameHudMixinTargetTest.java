package cn.net.rms.confluxmap.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;
import org.spongepowered.asm.mixin.injection.Redirect;

final class InGameHudMixinTargetTest {
    //#if MC>=260200
    //$$ private static final String HUD_CLASS = "net/minecraft/client/gui/Hud";
    //#elseif MC>=260100
    //$$ private static final String HUD_CLASS = "net/minecraft/client/gui/Gui";
    //#else
    private static final String HUD_CLASS = "net/minecraft/client/gui/hud/InGameHud";
    //#endif

    @Test
    void scoreboardRedirectMatchesTheVanillaFillInvocation() throws IOException {
        final Method redirector = Arrays.stream(InGameHudMixin.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("confluxmap$captureScoreboardFill"))
            .findFirst()
            .orElseThrow();
        final Redirect redirect = redirector.getAnnotation(Redirect.class);
        assertNotNull(redirect, "scoreboard bounds capture must remain a redirect");

        final MixinTargetProbe.Result result = MixinTargetProbe.probe(
            HUD_CLASS, redirect.method()[0],
            //#if MC>=260300
            //$$ redirect.at()[0].target()
            //#else
            redirect.at().target()
            //#endif
        );

        assertTrue(result.methodFound(), "the redirect's scoreboard target method must exist");
        assertEquals(
            result.methodStatic(),
            Modifier.isStatic(redirector.getModifiers()),
            "the redirect handler and its target method must have matching static modifiers"
        );
        assertTrue(
            result.invocationFound(),
            "the redirect's fill selector must match an invocation in the vanilla scoreboard method"
        );
    }

    //#if MC<12104
    @Test
    void crosshairInjectCapturesTheVanillaCrosshairParameters() throws IOException {
        assertCapturedParamsMatchVanilla(
            InGameHudMixin.class,
            "confluxmap$renderWaypointItemsBeforeCrosshair",
            "renderCrosshair"
        );
    }
    //#endif

    //#if MC<12100
    @Test
    void statusEffectInjectsCaptureTheVanillaOverlayParameters() throws IOException {
        assertCapturedParamsMatchVanilla(
            StatusEffectHudMixin.class,
            "confluxmap$beforeStatusEffects",
            "renderStatusEffectOverlay"
        );
        assertCapturedParamsMatchVanilla(
            StatusEffectHudMixin.class,
            "confluxmap$afterStatusEffects",
            "renderStatusEffectOverlay"
        );
    }
    //#elseif MC<260100
    //$$ @Test
    //$$ void statusEffectWrapCapturesTheVanillaOverlayParameters() throws IOException {
    //$$     assertCapturedParamsMatchVanilla(
    //$$         StatusEffectHudMixin.class,
    //$$         "confluxmap$renderStatusEffects",
    //$$         "renderStatusEffectOverlay"
    //$$     );
    //$$ }
    //#endif

    /**
     * Mixin validates captured parameters against the target only when the game loads, and a
     * name-only selector still resolves when the signature drifts, so compare the handler's
     * capture list with the vanilla bytecode here.
     */
    private static void assertCapturedParamsMatchVanilla(
        final Class<?> mixinClass,
        final String handlerName,
        final String targetMethod
    ) throws IOException {
        final Method handler = Arrays.stream(mixinClass.getDeclaredMethods())
            .filter(method -> method.getName().equals(handlerName))
            .findFirst()
            .orElseThrow(() -> new AssertionError(handlerName + " is missing from " + mixinClass));
        final Class<?>[] params = handler.getParameterTypes();
        final String tail = params[params.length - 1].getSimpleName();
        assertTrue(
            tail.equals("CallbackInfo") || tail.equals("CallbackInfoReturnable")
                || tail.equals("Operation"),
            handlerName + " must end with a mixin callback tail parameter"
        );
        final StringBuilder expected = new StringBuilder("(");
        for (int i = 0; i < params.length - 1; i++) {
            expected.append(Type.getDescriptor(params[i]));
        }
        expected.append(')');

        final List<String> descriptors = MixinTargetProbe.methodDescriptors(HUD_CLASS, targetMethod);
        assertFalse(descriptors.isEmpty(), targetMethod + " must exist in vanilla " + HUD_CLASS);
        assertTrue(
            descriptors.stream().anyMatch(descriptor -> descriptor.startsWith(expected.toString())),
            handlerName + " captures " + expected + " but vanilla " + targetMethod
                + " offers " + descriptors
        );
    }

    //#if MC<11900
    @Test
    void waypointItemRenderRestoresTheLegacyCrosshairAtlas() throws Exception {
        final String source = Files.readString(preprocessedSource());

        assertTrue(source.contains(
            "RenderSystem.setShaderTexture(0, DrawableHelper.GUI_ICONS_TEXTURE);"
        ));
    }

    private static Path preprocessedSource() throws URISyntaxException {
        Path current = Path.of(
            InGameHudMixinTargetTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()
        );
        while (current != null && !"build".equals(current.getFileName().toString())) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Could not locate the version build directory");
        }
        final Path preprocessed = current.resolve(
            "preprocessed/main/java/cn/net/rms/confluxmap/mixin/InGameHudMixin.java"
        );
        if (Files.exists(preprocessed)) {
            return preprocessed;
        }
        return current.getParent().getParent().getParent().resolve(
            "src/main/java/cn/net/rms/confluxmap/mixin/InGameHudMixin.java"
        );
    }
    //#endif
}
