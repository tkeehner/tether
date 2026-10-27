package com.tyler.primelink.link

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings kill switch. */
class LinkTile : TileService() {
    override fun onStartListening() { refresh() }
    override fun onClick() {
        val s = LinkStore(this)
        s.enabled = !s.enabled
        if (s.enabled) LinkService.start(this) else LinkService.stop(this)
        refresh()
    }
    private fun refresh() { qsTile?.apply { state = if (LinkStore(this@LinkTile).enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE; label = "Tether"; updateTile() } }
}
