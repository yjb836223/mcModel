package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.Baritone;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class MindFixCommand extends Command {

    public MindFixCommand(IBaritone baritone) {
        super(baritone, "mindfix");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireExactly(1);
        boolean enable = args.getAs(Boolean.class);
        Baritone.settings().mindfix.value = enable;
        logDirect("mindfix " + (enable ? "已开启" : "已关闭"));
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        return Stream.of("true", "false");
    }

    @Override
    public String getShortDesc() {
        return "自动修复稿子耐久 (需要经验修补附魔)";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
            "当所有稿子耐久均低于阈值时，暂停挖矿并挖 XP 矿修复。",
            "用法: #mindfix true/false"
        );
    }
}
