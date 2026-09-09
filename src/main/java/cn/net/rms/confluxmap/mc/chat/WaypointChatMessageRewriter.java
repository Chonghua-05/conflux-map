package cn.net.rms.confluxmap.mc.chat;

import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.waypoint.chat.WaypointChatClickPayload;
import cn.net.rms.confluxmap.core.waypoint.chat.WaypointChatCodec;
import java.util.Optional;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.ChatFormatting;

/** Rewrites recognized waypoint shares immediately before they enter the visible chat queue. */
public final class WaypointChatMessageRewriter {
    private WaypointChatMessageRewriter() {
    }

    public static Component rewrite(final Component original, final DimensionId receivedDimension) {
        final String visibleMessage = original.getString();
        final Optional<WaypointChatCodec.Candidate> parsed = WaypointChatCodec.parse(
            visibleMessage, receivedDimension
        );
        final Optional<String> payload = WaypointChatClickPayload.encode(
            visibleMessage, receivedDimension
        );
        if (!parsed.isPresent() || !payload.isPresent()) {
            return original;
        }

        final WaypointChatCodec.Candidate candidate = parsed.get();
        final MutableComponent visible = candidate.confluxFormat()
            ? compactConfluxMessage(original, visibleMessage, candidate)
            : Texts.literal("").append(original.copy());
        final MutableComponent importAction = Texts.translatable("confluxmap.chat.waypoint.import")
            .setStyle(Style.EMPTY
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(Texts.copyToClipboard(payload.get())));
        final Component rewritten = visible.append(Texts.literal(" ")).append(importAction);
        WaypointChatDiagnostics.rewrite(
            original, rewritten, receivedDimension, parsed, payload.isPresent()
        );
        return rewritten;
    }

    private static MutableComponent compactConfluxMessage(
        final Component original,
        final String visibleMessage,
        final WaypointChatCodec.Candidate candidate
    ) {
        final String compactLabel = WaypointChatCodec.formatCompactLabel(
            candidate, dimensionLabel(candidate.dimensionId())
        );
        if (original.getContents() instanceof TranslatableContents content
            && "chat.type.text".equals(content.getKey())) {
            final Object[] originalArgs = content.getArgs();
            if (originalArgs.length >= 2) {
                final Object[] compactArgs = originalArgs.clone();
                compactArgs[1] = Texts.literal(compactLabel);
                final MutableComponent compact = Texts.translatable(content.getKey(), compactArgs)
                    .setStyle(original.getStyle());
                for (final Component sibling : original.getSiblings()) {
                    compact.append(sibling.copy());
                }
                return compact;
            }
        }
        return Texts.literal(WaypointChatCodec.formatCompactMessage(
            visibleMessage, candidate, dimensionLabel(candidate.dimensionId())
        ));
    }

    private static String dimensionLabel(final DimensionId dimension) {
        if (dimension.equals(DimensionId.OVERWORLD)) {
            return Texts.translatable("confluxmap.dimension.overworld").getString();
        }
        if (dimension.equals(DimensionId.NETHER)) {
            return Texts.translatable("confluxmap.dimension.the_nether").getString();
        }
        if (dimension.equals(DimensionId.END)) {
            return Texts.translatable("confluxmap.dimension.the_end").getString();
        }
        return dimension.path();
    }
}
