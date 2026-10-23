package com.example.pathfinderindoor

import android.content.Intent
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun EmergencyAccessButton() {
    val context = LocalContext.current

    Button(
        onClick = {
            context.startActivity(
                Intent(context, EmergencyActivity::class.java)
            )
        }
    ) {
        Text("Emergency Contacts / SOS")
    }
}