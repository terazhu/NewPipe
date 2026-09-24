/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.download;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.PlayerView;

import org.schabi.newpipe.R;
import org.schabi.newpipe.util.ThemeHelper;

public final class OfflinePlayerActivity extends AppCompatActivity {
    private static final String EXTRA_URI = "offline_uri";
    private static final String EXTRA_TITLE = "offline_title";
    private static final String STATE_POSITION = "offline_position";

    private ExoPlayer player;
    private long playbackPosition;

    public static Intent getIntent(@NonNull final Context context,
                                   @NonNull final Uri uri,
                                   @NonNull final String title) {
        return new Intent(context, OfflinePlayerActivity.class)
                .putExtra(EXTRA_URI, uri.toString())
                .putExtra(EXTRA_TITLE, title);
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        ThemeHelper.setTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_offline_player);
        setTitle(getIntent().getStringExtra(EXTRA_TITLE));
        if (savedInstanceState != null) {
            playbackPosition = savedInstanceState.getLong(STATE_POSITION);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        final String uriText = getIntent().getStringExtra(EXTRA_URI);
        if (uriText == null) {
            finish();
            return;
        }

        final PlayerView playerView = findViewById(R.id.offline_player_view);
        player = new ExoPlayer.Builder(this).build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(@NonNull final PlaybackException error) {
                Toast.makeText(OfflinePlayerActivity.this,
                        R.string.offline_playback_failed, Toast.LENGTH_LONG).show();
            }
        });
        playerView.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(Uri.parse(uriText)));
        player.prepare();
        player.seekTo(playbackPosition);
        player.play();
    }

    @Override
    protected void onStop() {
        if (player != null) {
            playbackPosition = player.getCurrentPosition();
            player.release();
            player = null;
        }
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(@NonNull final Bundle outState) {
        outState.putLong(STATE_POSITION, playbackPosition);
        super.onSaveInstanceState(outState);
    }
}
