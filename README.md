# MC-1.20.1-AuthPlugin

AuthPlugin is a Minecraft 1.20.1 server-side authentication plugin based on Architectury (Fabric + Forge).

It uses token-based login (`/login <token>`) to verify players, automatically logs in bound players when they rejoin, and blocks gameplay actions for unauthenticated players. The plugin also provides an embedded web admin panel for token management (generate, batch generate, disable, delete) and persists data with H2.
