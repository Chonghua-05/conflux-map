package cn.net.rms.confluxmap.compat;
import java.net.URI;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;

/**
 * The one place that knows how this Minecraft version builds a text component.
 *
 * <p>1.19 deleted {@code LiteralText}/{@code TranslatableText} in favour of the {@code Text}
 * static factories. That rename reaches ~135 call sites across the UI, chat and command code, so
 * routing every one of them through these two methods keeps the version fork to a single seam
 * instead of scattering version-specific branches over many files.
 */
public final class Texts {
    private Texts() {
    }

    /** A translated component for {@code key}, with optional format arguments. */
    public static MutableComponent translatable(final String key, final Object... args) {
        return Component.translatable(key, args);
    }

    /** A literal, untranslated component. */
    public static MutableComponent literal(final String text) {
        return Component.literal(text);
    }

    public static ClickEvent copyToClipboard(final String value) {
        return new ClickEvent.CopyToClipboard(value);
    }

    public static ClickEvent openUrl(final String value) {
        return new ClickEvent.OpenUrl(URI.create(value));
    }

    public static ClickEvent runCommand(final String command) {
        return new ClickEvent.RunCommand(command);
    }

    public static HoverEvent showText(final Component value) {
        return new HoverEvent.ShowText(value);
    }

    /** String payload carried by a click event, or null for event variants without one. */
    public static String clickValue(final ClickEvent event) {
        return event instanceof ClickEvent.CopyToClipboard copy ? copy.value() : null;
    }

    /** Run-command payload carried by a click event, or null for every other action. */
    public static String runCommandValue(final ClickEvent event) {
        if (event == null) {
            return null;
        }
        return event instanceof ClickEvent.RunCommand run ? run.command() : null;
    }
}
