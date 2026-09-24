# TubeCache

TubeCache is a personal NewPipe fork focused on a channel-organized offline library.

## Features

- Receives YouTube links from Android's share menu.
- Downloads video, audio, and subtitles into app-owned storage.
- Stores downloads under `TubeCache/<channel>/`.
- Shows Downloads as a permanent home tab.
- Groups completed downloads by channel.
- Plays downloaded media inside the app.
- Opens a local copy when the same source URL is opened again.
- Hides current and ended live streams from lists and subscription feeds.

## Subscriptions

NewPipe does not sign in to a Google account. Export YouTube subscriptions with Google Takeout,
then use `Subscriptions > Import/Export > Import from YouTube`. Imported subscriptions and channel
groups remain local to TubeCache.

## Storage

Downloads use the application's external files directory. Android removes this directory when the
app is uninstalled, so export important files before uninstalling.

Only download content when you have permission from the copyright holder or the platform permits
offline storage.
