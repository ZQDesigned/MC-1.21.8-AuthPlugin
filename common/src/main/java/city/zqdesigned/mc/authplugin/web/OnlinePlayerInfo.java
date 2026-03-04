package city.zqdesigned.mc.authplugin.web;

import java.util.UUID;

public record OnlinePlayerInfo(UUID uuid, String name, boolean loggedIn, String boundToken) {
}
