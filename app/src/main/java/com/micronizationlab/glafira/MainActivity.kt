package com.micronizationlab.glafira

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BackgroundBlack = Color(0xFF000000)
private val IcqGreen = Color(0xFF7EC850)
private val SecondaryGray = Color(0xFF888888)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GlafiraMainScreen()
        }
    }
}

@Composable
fun GlafiraMainScreen() {
    Surface(modifier = Modifier.fillMaxSize(), color = BackgroundBlack) {
        Box(modifier = Modifier.fillMaxSize().background(BackgroundBlack)) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "GLAFIRA AI HUB",
                    color = IcqGreen,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "v0.1 — каркас",
                    color = SecondaryGray,
                    fontSize = 12.sp
                )
            }
            Text(
                text = "Sprint 0 готов",
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                color = IcqGreen,
                fontSize = 12.sp
            )
        }
    }
}
