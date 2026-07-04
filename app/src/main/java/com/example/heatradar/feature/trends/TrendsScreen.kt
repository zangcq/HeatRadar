package com.example.heatradar.feature.trends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.heatradar.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrendsScreen(viewModel: TrendsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.title_trends)) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    selected = uiState.selectedRange == TrendRange.OneHour,
                    onClick = { viewModel.selectRange(TrendRange.OneHour) },
                    shape = MaterialTheme.shapes.extraSmall
                ) { Text("1 小时") }
                SegmentedButton(
                    selected = uiState.selectedRange == TrendRange.TwentyFourHours,
                    onClick = { viewModel.selectRange(TrendRange.TwentyFourHours) },
                    shape = MaterialTheme.shapes.extraSmall
                ) { Text("24 小时") }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (uiState.selectedRange == TrendRange.OneHour) {
                            "1 小时趋势占位"
                        } else {
                            "24 小时趋势占位"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}
