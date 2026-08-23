package org.osmium.discord;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * A CommandSource that records everything the command pipeline emits,
 * so Discord /console can show the actual result instead of just
 * "executed".
 */
public final class CapturingCommandSource implements CommandSource {

    private final CommandSource delegate; // console source, for getBukkitSender
    private final List<String> lines = new ArrayList<>();

    public CapturingCommandSource(CommandSource consoleDelegate) {
        this.delegate = consoleDelegate;
    }

    @Override
    public void sendSystemMessage(Component message) {
        if (lines.size() < 60) lines.add(strip(message.getString()));
    }

    @Override
    public boolean acceptsSuccess() { return true; }

    @Override
    public boolean acceptsFailure() { return true; }

    @Override
    public boolean shouldInformAdmins() { return false; }

    @Override
    public org.bukkit.command.CommandSender getBukkitSender(CommandSourceStack stack) {
        return delegate.getBukkitSender(stack);
    }

    /** Plain-text capture, newest last, capped. */
    public String captured() {
        if (lines.isEmpty()) return "(no output)";
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, lines.size() - 20);
        for (int i = from; i < lines.size(); i++) sb.append(lines.get(i)).append('\n');
        String s = sb.toString();
        return s.length() > 1800 ? "…" + s.substring(s.length() - 1800) : s;
    }

    private static String strip(String s) {
        return s == null ? "" : s.replaceAll("\u00a7.", "");
    }
}
