package work.keepwriting.dshanywhere

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * 思考/进行中指示条：三枚扁平胶囊（14×5dp）错相位脉动，颜色在主题 primary 与
 * tertiary 间渐变——替代原小圆形转圈（用户反馈：要更扁、更美观、可变色）。
 * 用于消息发送中、Bot 正在输入/思考、附件上传中等状态。
 */
@Composable
fun ThinkingPill(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "thinking")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    val base = MaterialTheme.colorScheme.primary
    val alt = MaterialTheme.colorScheme.tertiary
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            // 每枚错开 1/3 周期：t ∈ [0,1)，透明度正弦脉动、颜色随之插值
            val t = (((phase - i) % 3f) + 3f) % 3f / 3f
            val pulse = sin(t * PI).toFloat().coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .size(width = 14.dp, height = 5.dp)
                    .alpha(0.35f + 0.65f * pulse)
                    .background(lerp(base, alt, t), RoundedCornerShape(3.dp)),
            )
        }
    }
}
