package org.fieldproof

import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fieldproof.ui.FieldproofRoot
import org.fieldproof.ui.theme.FieldproofTheme

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val repo = FieldproofApp.instance.repo

        // Language is preset on the surveyor's profile; the Home dropdown changes it.
        lifecycleScope.launch {
            repo.state.map { it.language }.distinctUntilChanged().collect { lang ->
                val cur = AppCompatDelegate.getApplicationLocales().toLanguageTags()
                if (cur != lang) AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang))
            }
        }
        // While the app is open, keep trying to push anything saved on the phone.
        lifecycleScope.launch {
            while (isActive) {
                delay(20_000)
                if (repo.state.value.outbox.isNotEmpty()) repo.syncOnce()
            }
        }
        setContent {
            FieldproofTheme { FieldproofRoot() }
        }
    }

    override fun onResume() {
        super.onResume()
        val repo = FieldproofApp.instance.repo
        repo.scope.launch { if (repo.state.value.outbox.isNotEmpty()) repo.syncOnce() else repo.refresh() }
    }
}
