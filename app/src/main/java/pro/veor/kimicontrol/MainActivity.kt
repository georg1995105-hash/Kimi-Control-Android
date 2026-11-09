package pro.veor.kimicontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/** Entry point. The entire UI lives in ConnectedScreen, not an obsolete prototype. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ConnectedScreen() }
    }
}
