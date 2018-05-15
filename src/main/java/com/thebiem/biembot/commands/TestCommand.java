package com.thebiem.biembot.commands;

import sx.blah.discord.handle.impl.events.guild.channel.message.MessageReceivedEvent;
import sx.blah.discord.handle.obj.IUser;

@Command(commandName = "test", description = "Test")
public class TestCommand implements ICommand {

    @CommandImplementation
    public void testCommandImpl(MessageReceivedEvent event, String ping) {
        event.getChannel().sendMessage("hi " + ping);
    }

    @SubCommand(commandName = "subcommand")
    public static class Ping implements ICommand {

        @CommandImplementation
        public void pingUser(MessageReceivedEvent event, IUser user) {
            event.getChannel().sendMessage("hi there " + user.mention());
        }
    }
}
