package com.thebiem.biembot;

import ch.qos.logback.classic.Level;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Provides;
import com.thebiem.biembot.commands.CommandImplementation;
import com.thebiem.biembot.commands.ICommand;
import com.thebiem.biembot.commands.SubCommand;
import com.thebiem.biembot.commands.TestCommand;
import com.thebiem.biembot.config.BotConfig;
import lombok.Cleanup;
import lombok.Data;
import lombok.Getter;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sx.blah.discord.api.ClientBuilder;
import sx.blah.discord.api.IDiscordClient;
import sx.blah.discord.api.events.EventSubscriber;
import sx.blah.discord.handle.impl.events.guild.channel.message.MessageReceivedEvent;
import sx.blah.discord.handle.obj.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

public class BiemBot {
    public static void main(String[] args) {
        ArgumentParser argumentParser = ArgumentParsers.newFor("BiemBot")
                .build()
                .defaultHelp(true)
                .description("BiemBot Main");

        argumentParser.addArgument("--debug", "-d")
                .required(false)
                .setDefault(false)
                .action(Arguments.storeTrue());

        Namespace namespace;

        try {
            namespace = argumentParser.parseArgs(args);
        } catch (ArgumentParserException e) {
            e.printStackTrace();
            System.exit(-1);
            return;
        }

        if (namespace.getBoolean("debug")) {
            ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
            root.setLevel(Level.DEBUG);
        }

        BiemBot.create();
    }

    private static BiemBot instance;

    private static void create() {
        if (instance != null) {
            throw new RuntimeException("Bot already initialised!");
        }

        instance = new BiemBot();
    }

    private BiemBot() {
        this.logger = LoggerFactory.getLogger(getClass());
        this.injector = Guice.createInjector(new BotModule());
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .create();

        File configFile = new File("./config.json");
        BotConfig config;
        try {
            config = this.gson.fromJson(new FileReader(configFile), BotConfig.class);
        } catch (FileNotFoundException e) {
            config = new BotConfig();
            config.setBotPrefix("biem!");
            config.setStartingActivity("watching");
            config.setStartingStatus("Anime!");

            String jsonString = this.gson.toJson(config);
            try {
                @Cleanup FileOutputStream fileOutputStream = new FileOutputStream(configFile);
                fileOutputStream.write(jsonString.getBytes(Charset.forName("UTF-8")));
            } catch (Exception e1) {
                logger.error("Failed to write config!", e1);
            }
        }

        this.botConfig = config;

        this.discordClient = new ClientBuilder()
                .registerListener(this)
                .setPresence(StatusType.ONLINE, ActivityType.valueOf(botConfig.getStartingActivity().toUpperCase()), botConfig.getStartingStatus())
                .withToken(System.getenv("BOT_TOKEN"))
                .build();

        this.discordClient.login();

        this.commands = new HashMap<>();
        commands.put("test", new CommandDefinition(new TestCommand(), Collections.singletonList(new TestCommand.Ping())));
    }

    private final Logger logger;
    private final @Getter Injector injector;
    private final Gson gson;
    private final IDiscordClient discordClient;
    private final BotConfig botConfig;

    private final HashMap<String, CommandDefinition> commands;

    @EventSubscriber
    public void onMessageReceived(MessageReceivedEvent event) {
        String message = event.getMessage().getContent();

        if(!message.startsWith(botConfig.getBotPrefix()) && !message.startsWith(discordClient.getOurUser().mention(false))) {
            return;
        }

        String commandString = message.trim().substring((message.startsWith(botConfig.getBotPrefix())
                ? botConfig.getBotPrefix().length()
                : discordClient.getOurUser().mention(false).length())).trim();

        String[] commandParts = commandString.split(" +");

        logger.debug("Handling command string: {}, parts: {}", commandParts, commandParts.length);

        String commandBase = commandParts[0].toLowerCase();

        if(commandBase.isEmpty()) {
            //TODO: Info command or something
            event.getChannel().sendMessage("<@256815828173848576> pls fix, info command or something here");
            return;
        }

        if(!commands.containsKey(commandBase)) {
            // TODO: Missing command embed
            event.getChannel().sendMessage("<@256815828173848576> pls fancy missing command embed");
            return;
        }

        CommandDefinition definition = commands.get(commandBase);
        List<ICommand> subcommands = definition.subcommands;

        ICommand commandToExecute = null;

        for(ICommand command : subcommands) {
            SubCommand subCommand = command.getClass().getAnnotation(SubCommand.class);
            if(subCommand.commandName().equalsIgnoreCase(commandParts[1])) {
                commandToExecute = command;
                String[] newCommandParts = new String[commandParts.length - 1];
                System.arraycopy(commandParts, 1, newCommandParts, 0, newCommandParts.length);
                commandParts = newCommandParts;
            }
        }

        if(commandToExecute == null) {
            commandToExecute = definition.instance;
        }

        Object[] parameters = new Object[commandParts.length - 1];
        ICommand.ArgumentType[] commandArguments = new ICommand.ArgumentType[parameters.length];

        Method methodToExecute = null;
        Method[] commandMethods = commandToExecute.getClass().getMethods();

        for(Method method : commandMethods) {
            if(method.isAnnotationPresent(CommandImplementation.class) && method.getParameterCount() - 1 == parameters.length) {
                methodToExecute = method;
                break;
            }
        }

        if(methodToExecute == null) {
            // TODO: Handle unknown arguments
            event.getChannel().sendMessage("<@256815828173848576> pls fancy invalid arguments embed");
            return;
        }

        logger.debug("Method to execute: {}, parameter count: {}, parameter types: {}", methodToExecute.getName(), methodToExecute.getParameterCount(), Arrays.toString(methodToExecute.getParameterTypes()));

        for(int i = 0; i < methodToExecute.getParameterCount() - 1; i++) {
            Class<?> type = methodToExecute.getParameterTypes()[i + 1];

            if(String.class.isAssignableFrom(type)) {
                commandArguments[i] = ICommand.ArgumentType.STRING;
            } else if(Number.class.isAssignableFrom(type)) {
                commandArguments[i] = ICommand.ArgumentType.NUMBER;
            } else if(IUser.class.isAssignableFrom(type)) {
                commandArguments[i] = ICommand.ArgumentType.USER;
            } else if(IChannel.class.isAssignableFrom(type)) {
                commandArguments[i] = ICommand.ArgumentType.CHANNEL;
            } else if(IRole.class.isAssignableFrom(type)) {
                commandArguments[i] = ICommand.ArgumentType.ROLE;
            } else {
                logger.debug("Unknown type: {}", type.getCanonicalName());
                commandArguments[i] = ICommand.ArgumentType.STRING;
            }

            logger.debug("Parameter type for {} is {}!", type.getCanonicalName(), commandArguments[i].name());
        }

        for(int i = 0; i < parameters.length; i++) {
            ICommand.ArgumentType argumentType = commandArguments[i];
            String rawArgument = commandParts[i + 1];

            // TODO: PARSE!!
            logger.debug("Ignoring argument type {}, using STRING for now!", argumentType.name());

            parameters[i] = rawArgument;
        }

        Object[] invokeArguments = new Object[parameters.length + 1];
        invokeArguments[0] = event;
        System.arraycopy(parameters, 0, invokeArguments, 1, parameters.length);

        try {
            methodToExecute.invoke(commandToExecute, invokeArguments);
        } catch (IllegalAccessException | InvocationTargetException | IllegalArgumentException e) {
            e.printStackTrace();
            event.getChannel().sendMessage(e.getClass().getCanonicalName());
        }
    }

    @Data
    private class CommandDefinition {
        private final ICommand instance;
        private final List<ICommand> subcommands;
    }

    private class BotModule extends AbstractModule {

        @Override
        protected void configure() {

        }

        @Provides
        BiemBot provideBiemBot() {
            return instance;
        }

        @Provides
        IDiscordClient provideDiscordClient() {
            return discordClient;
        }

        @Provides
        BotConfig provideBotConfig() {
            return botConfig;
        }
    }
}
