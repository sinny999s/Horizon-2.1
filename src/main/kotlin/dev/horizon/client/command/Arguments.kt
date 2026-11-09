package dev.horizon.client.command

import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import dev.horizon.client.module.Config
import dev.horizon.client.config.Friends
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import net.minecraft.client.Minecraft
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import java.util.concurrent.CompletableFuture

/** A module by id, suggesting every module's id. */
object ModuleArgument : ArgumentType<Module> {
    override fun parse(reader: StringReader): Module {
        val id = reader.readString()
        // The string is read a second time for the error (on purpose), so the message names the next word (usually nothing).
        return Modules.all.firstOrNull { it.id == id }
            ?: throw DynamicCommandExceptionType { Component.literal("Module %s does not exist".format(it)) }.create(reader.readString())
    }

    override fun <S> listSuggestions(context: CommandContext<S>, builder: SuggestionsBuilder): CompletableFuture<Suggestions> =
        SharedSuggestionProvider.suggest(Modules.all.stream().map { it.id }, builder)
}

/** One of the friends' names. */
object FriendArgument : ArgumentType<String> {
    override fun parse(reader: StringReader): String {
        val name = reader.readString()
        if (!Friends.isFriend(name)) {
            throw DynamicCommandExceptionType { Component.literal("Friend with name %s does not exist".format(it)) }.create(name)
        }
        return name
    }

    override fun <S> listSuggestions(context: CommandContext<S>, builder: SuggestionsBuilder): CompletableFuture<Suggestions> =
        SharedSuggestionProvider.suggest(Friends.names, builder)
}

/** The name of a module config. */
object ConfigArgument : ArgumentType<String> {
    override fun parse(reader: StringReader): String {
        val name = reader.readString()
        if (name !in Config.list.map { it.id }) {
            throw DynamicCommandExceptionType { Component.literal("Config with name %s does not exist".format(it)) }.create(name)
        }
        return name
    }

    override fun <S> listSuggestions(context: CommandContext<S>, builder: SuggestionsBuilder): CompletableFuture<Suggestions> =
        SharedSuggestionProvider.suggest(Config.list.map { it.id }, builder)
}

/** Any name, suggesting the players on the tab list. */
object PlayerArgument : ArgumentType<String> {
    override fun parse(reader: StringReader): String {
        val name = reader.readString()
        val info = Minecraft.getInstance().connection?.onlinePlayers?.firstOrNull { it.profile.name == name } ?: return name
        return info.profile.name
    }

    override fun <S> listSuggestions(context: CommandContext<S>, builder: SuggestionsBuilder): CompletableFuture<Suggestions> {
        val players = Minecraft.getInstance().connection?.onlinePlayers ?: return Suggestions.empty()
        return SharedSuggestionProvider.suggest(players.stream().map { it.profile.name }, builder)
    }
}
