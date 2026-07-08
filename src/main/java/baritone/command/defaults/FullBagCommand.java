package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.Baritone;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class FullBagCommand extends Command {

    public FullBagCommand(IBaritone baritone) {
        super(baritone, "fullbag");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireExactly(1);
        boolean enable = args.getAs(Boolean.class);
        Baritone.settings().fullbag.value = enable;
        logDirect("fullbag " + (enable ? "已开启" : "已关闭"));
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        return Stream.of("true", "false");
    }

    @Override
    public String getShortDesc() {
        return "背包满时自动压缩到潜影盒";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
            "背包满时将挖矿产物存入潜影盒，腾出空间继续挖矿。",
            "用法: #fullbag true/false"
        );
    }
}
