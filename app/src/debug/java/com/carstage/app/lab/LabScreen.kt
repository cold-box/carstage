package com.carstage.app.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carstage.app.audio.AudioSources
import com.carstage.app.audio.SignalAnalysis

/** Developer screen for verifying microphone and vehicle-control behavior on the Pleos emulator. */
@Composable
fun LabScreen(viewModel: LabViewModel, onRequestMicPermission: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy = state.runningProbe != null

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Row(modifier = Modifier.fillMaxSize().padding(32.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(modifier = Modifier.width(560.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("CarStage Lab", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "에뮬레이터에서 마이크와 차량 제어를 검증합니다",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!state.micGranted) {
                    Button(onClick = onRequestMicPermission) { Text("마이크 권한 요청") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = viewModel::runAll, enabled = !busy) { Text("전체 진단") }
                    OutlinedButton(onClick = viewModel::runAudioProbe, enabled = !busy) { Text("오디오") }
                    OutlinedButton(onClick = viewModel::runVehicleProbe, enabled = !busy) { Text("차량") }
                    OutlinedButton(onClick = viewModel::runPlatformProbe, enabled = !busy) { Text("플랫폼") }
                    OutlinedButton(onClick = viewModel::runStageProbe, enabled = !busy) { Text("연출") }
                    OutlinedButton(onClick = viewModel::runDriveProbe, enabled = !busy) { Text("주행 상태") }
                    OutlinedButton(onClick = viewModel::runNowPlayingProbe, enabled = !busy) { Text("재생 중인 곡") }
                }
                OutlinedButton(onClick = viewModel::saveMicSample, enabled = !busy && state.micGranted) {
                    Text("5초 녹음 저장 (개발 분석용)")
                }
                Text(state.runningProbe?.let { "실행 중: $it" } ?: "대기 중", style = MaterialTheme.typography.titleMedium)

                Spacer(Modifier.height(8.dp))
                Text("실시간 마이크", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AudioSources.all.take(3).forEach { source ->
                        FilterChip(
                            selected = state.liveSource == source,
                            onClick = { viewModel.selectLiveSource(source) },
                            label = { Text(source.name) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AudioSources.all.drop(3).forEach { source ->
                        FilterChip(
                            selected = state.liveSource == source,
                            onClick = { viewModel.selectLiveSource(source) },
                            label = { Text(source.name) },
                        )
                    }
                }
                Button(onClick = viewModel::toggleLive, enabled = !busy && state.micGranted) {
                    Text(if (state.live == null) "측정 시작" else "측정 중지")
                }
                LiveMeter(state.live)
            }
            LogPanel(state.logs, modifier = Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun LiveMeter(live: LiveLevels?) {
    val level = live?.levelDb ?: SignalAnalysis.SILENCE_DB
    val fraction = ((level + 60f) / 60f).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        Text("레벨 %.1f dBFS".format(level), style = MaterialTheme.typography.titleMedium)
        Text(
            "음높이 ${live?.pitchHz?.let { "%.0f Hz (%s)".format(it, live.note) } ?: "-"}",
            style = MaterialTheme.typography.titleMedium,
        )
        Text("박수 감지 ${live?.claps ?: 0}회", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun LogPanel(logs: List<String>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.lastIndex)
    }
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
        LazyColumn(state = listState, modifier = Modifier.padding(16.dp)) {
            items(logs) { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            }
        }
    }
}
