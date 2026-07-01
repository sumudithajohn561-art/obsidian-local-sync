package com.obsidian.quickcapture

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.obsidian.quickcapture.content.ContentExtractor
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action != Intent.ACTION_SEND) { finish(); return }

        scope.launch(Dispatchers.IO) {
            try {
                val content = ContentExtractor.extract(intent)
                val fileName = CaptureRepository.save(content, contentResolver)
                withContext(Dispatchers.Main) {
                    if (fileName != null) {
                        Toast.makeText(this@MainActivity, "✅ ${content.title.take(20)}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "❌ 保存失败(收件箱)", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "❌ ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            finish()
        }
    }
}
