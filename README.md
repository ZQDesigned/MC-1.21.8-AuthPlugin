# MC-1.21.8-AuthPlugin

AuthPlugin is a Minecraft 1.21.8 server-side authentication plugin based on Architectury (Fabric + NeoForge).

It uses token-based login (`/login <token>`) to verify players, automatically logs in bound players when they rejoin, and blocks gameplay actions for unauthenticated players. The plugin also provides an embedded web admin panel for token management (generate, batch generate, disable, delete) and persists data with H2.
