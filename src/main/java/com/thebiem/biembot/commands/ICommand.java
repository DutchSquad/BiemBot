package com.thebiem.biembot.commands;

public interface ICommand {
    // This is a no-op interface for now, just so we can use reflections to get all the commands in a package

    enum ArgumentType {
        NUMBER, STRING, USER, CHANNEL, ROLE
    }
}
