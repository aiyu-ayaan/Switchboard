package com.switchboard.app.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.lifecycle.lifecycleScope
import com.switchboard.app.ui.SwitchboardTheme
import kotlinx.coroutines.launch

/**
 * Chooses which buttons a single widget carries.
 *
 * Runs twice over a widget's life: once as the launcher's configuration step
 * when it is placed, and again whenever the gear on the widget is tapped. The
 * two differ only in what happens on cancel -- a placement that is cancelled
 * must leave no widget behind, which is what the [Activity.RESULT_CANCELED]
 * default result achieves, while a later edit simply closes.
 *
 * The host list is not configured here. It is whatever the app has paired, so
 * a machine appears the moment it is paired and disappears when it is
 * forgotten; there is nothing for the user to keep in sync by hand.
 */
class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        // Set before anything can go wrong: the launcher reads this result, and
        // a configuration the user backs out of must not leave a widget behind.
        setResult(Activity.RESULT_CANCELED, resultIntent())

        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(this@WidgetConfigActivity)
                .getGlanceIdBy(appWidgetId)
            val stored = getAppWidgetState(
                this@WidgetConfigActivity,
                androidx.glance.state.PreferencesGlanceStateDefinition,
                glanceId
            )
            val initial = SwitchboardWidget.selectedActionIds(stored).map { it.id }.toSet()

            setContent {
                SwitchboardTheme {
                    ConfigScreen(
                        initial = initial,
                        onCancel = ::finish,
                        onSave = { ids ->
                            lifecycleScope.launch {
                                SwitchboardWidget.saveSelection(
                                    this@WidgetConfigActivity,
                                    glanceId,
                                    ids
                                )
                                setResult(Activity.RESULT_OK, resultIntent())
                                finish()
                            }
                        }
                    )
                }
            }
        }
    }

    private fun resultIntent() =
        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)

    companion object {
        /** Reopens this screen for a widget already on the home screen. */
        fun reconfigureIntent(context: Context, appWidgetId: Int): Intent =
            Intent(context, WidgetConfigActivity::class.java)
                .setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigScreen(
    initial: Set<String>,
    onCancel: () -> Unit,
    onSave: (Set<String>) -> Unit
) {
    var selected by remember { mutableStateOf(initial) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Widget controls") }) }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                text = "Every paired desktop appears on the widget automatically. " +
                    "Pick the buttons each one carries.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(WIDGET_ACTIONS, key = { it.id }) { action ->
                    val on = action.id in selected
                    ListItem(
                        leadingContent = {
                            Icon(painterResource(action.iconRes), contentDescription = null)
                        },
                        headlineContent = { Text(action.label) },
                        supportingContent = {
                            Text(
                                if (action.disruptive) "${action.description} - interrupts what you are doing"
                                else action.description
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = on,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + action.id
                                    else selected - action.id
                                }
                            )
                        }
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                Button(
                    onClick = { onSave(selected) },
                    modifier = Modifier.padding(start = 8.dp)
                ) { Text("Save") }
            }
        }
    }
}
