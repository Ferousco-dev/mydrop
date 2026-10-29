package com.mydrop.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RoundIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Column(
        Modifier.size(40.dp).clip(CircleShape).background(MyDropColors.Card).clickable(onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun SettingIcon(icon: ImageVector, color: Color) {
    Column(
        Modifier.size(38.dp).clip(CircleShape).background(color),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(21.dp))
    }
}

@Composable
fun MyDropSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = Modifier.scale(0.85f),
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = MyDropColors.Green,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = MyDropColors.Track,
            uncheckedBorderColor = Color.Transparent,
        ),
    )
}

@Composable
fun SettingToggleCard(
    icon: ImageVector, color: Color, title: String,
    checked: Boolean, onCheckedChange: (Boolean) -> Unit, footer: String,
    cardColor: Color = MyDropColors.Card,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cardColor)
                .clickable { onCheckedChange(!checked) }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingIcon(icon, color)
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
            MyDropSwitch(checked, onCheckedChange)
        }
        Footer(footer)
    }
}

@Composable
fun SettingNavCard(
    icon: ImageVector, color: Color, title: String, footer: String? = null,
    cardColor: Color = MyDropColors.Card, onClick: () -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cardColor)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingIcon(icon, color)
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
            Icon(Icons.Rounded.ChevronRight, null, tint = MyDropColors.Secondary)
        }
        if (footer != null) Footer(footer) else Spacer(Modifier.height(0.dp))
    }
}

@Composable
fun Footer(text: String) {
    Text(text, color = MyDropColors.Secondary, fontSize = 12.sp, lineHeight = 17.sp,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp))
}
