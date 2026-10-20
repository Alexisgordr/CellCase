package com.alexisgordr.cellcase

import android.app.Application
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.alexisgordr.cellcase.data.CaseRepository
import com.alexisgordr.cellcase.ui.CaseListScreen
import com.alexisgordr.cellcase.ui.CaseScreen

class CellCaseApp : Application() {
    lateinit var repository: CaseRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = CaseRepository(this)
    }
}

/**
 * AppCompatActivity: necesaria para el idioma por aplicación con AppCompat en Android 8–12 (D-045). El tema debe
 * ser de AppCompat (Theme.AppCompat.DayNight.NoActionBar); los colores los pone Material 3 en Compose.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            val colors = if (dark) darkColorScheme(primary = Color(0xFF9CC3E6)) else lightColorScheme(primary = Color(0xFF1F3A5F))
            MaterialTheme(colorScheme = colors) {
                var openCase by rememberSaveable { mutableStateOf<String?>(null) }
                val current = openCase
                if (current == null) {
                    CaseListScreen(onOpen = { openCase = it })
                } else {
                    BackHandler { openCase = null }
                    CaseScreen(caseId = current, onBack = { openCase = null })
                }
            }
        }
    }
}
