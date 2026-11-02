package com.aiyu.switchboard.quick

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Base for the three Quick Settings tiles; each is one tap on the computer last used. */
abstract class QuickTile(private val kind: Quick) : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        qsTile?.apply {
            label = kind.tileLabel
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            updateTile()
        }
        scope.launch {
            val message = QuickActions.run(applicationContext, kind)
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            qsTile?.apply {
                state = Tile.STATE_INACTIVE
                updateTile()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

class MuteTile : QuickTile(Quick.Mute)

class PlayPauseTile : QuickTile(Quick.PlayPause)

class LockTile : QuickTile(Quick.Lock)
