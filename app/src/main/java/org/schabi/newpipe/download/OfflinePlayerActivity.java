/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.download;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.PlayerView;

import org.schabi.newpipe.R;
import org.schabi.newpipe.learning.AiLearningDialog;
import org.schabi.newpipe.learning.SubtitleRepository;
import org.schabi.newpipe.util.ThemeHelper;

import java.util.List;

public final class OfflinePlayerActivity extends AppCompatActivity {
    private static final String EXTRA_URI = "offline_uri";
    private static final String EXTRA_TITLE = "offline_title";
    private static final String EXTRA_SOURCE = "offline_source";
    private static final String STATE_POSITION = "offline_position";

    private ExoPlayer player;
    private long playbackPosition;
    private String source;
    private String title;
    private Uri mediaUri;
    private SubtitleRepository.CachedSubtitle subtitle;

    public static Intent getIntent(@NonNull final Context context,
                                   @NonNull final Uri uri,
                                   @NonNull final String title,
                                   @NonNull final String source) {
        return new Intent(context, OfflinePlayerActivity.class)
                .putExtra(EXTRA_URI, uri.toString())
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_SOURCE, source);
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        ThemeHelper.setTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_offline_player);
        title = getIntent().getStringExtra(EXTRA_TITLE);
        source = getIntent().getStringExtra(EXTRA_SOURCE);
        setTitle(title);
        final View aiButton = findViewById(R.id.offline_ai_learning);
        aiButton.setVisibility(View.VISIBLE);
        aiButton.setOnClickListener(v -> openLearning());
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
        mediaUri = Uri.parse(uriText);

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
        subtitle = source == null
                ? null : SubtitleRepository.find(this, source);
        player.setMediaItem(buildMediaItem());
        player.prepare();
        player.seekTo(playbackPosition);
        player.play();
    }

    private MediaItem buildMediaItem() {
        final MediaItem.Builder media = new MediaItem.Builder().setUri(mediaUri);
        if (subtitle != null) {
            media.setSubtitleConfigurations(List.of(
                    new MediaItem.SubtitleConfiguration.Builder(subtitle.uri)
                            .setMimeType(subtitle.mimeType)
                            .setLanguage(subtitle.language)
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build()));
        }
        return media.build();
    }

    private void openLearning() {
        if (subtitle != null) {
            showLearning();
            return;
        }
        if (source == null || source.isEmpty()) {
            Toast.makeText(this, R.string.no_subtitles_available, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, R.string.loading_subtitles, Toast.LENGTH_SHORT).show();
        SubtitleRepository.cacheFromSource(this, source, (loaded, error) -> {
            if (loaded == null || isFinishing()) {
                Toast.makeText(this, R.string.no_subtitles_available,
                        Toast.LENGTH_LONG).show();
                return;
            }
            subtitle = loaded;
            if (player != null) {
                final long position = player.getCurrentPosition();
                final boolean playWhenReady = player.getPlayWhenReady();
                player.setMediaItem(buildMediaItem(), position);
                player.prepare();
                player.setPlayWhenReady(playWhenReady);
            }
            showLearning();
        });
    }

    private void showLearning() {
        AiLearningDialog.show(this, title == null ? "" : title, subtitle,
                () -> player == null ? playbackPosition : player.getCurrentPosition());
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
