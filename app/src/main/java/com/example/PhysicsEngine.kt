package com.example

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.random.Random

enum class ShapeType { CIRCLE, SQUARE, RECT }

class PhysicsObject(
    var x: Float,
    var y: Float,
    var vx: Float = 0f,
    var vy: Float = 0f,
    val type: ShapeType,
    val size: Float,
    val sizeY: Float = size,
    val color: Color
) {
    var lastX: Float = x
    var lastY: Float = y
}

class PhysicsEngine {
    val objects = mutableListOf<PhysicsObject>()
    private var toneGen: ToneGenerator? = null
    
    var width: Float = 1f
    var height: Float = 1f
    
    var isPinching: Boolean = false
    var grabbedObject: PhysicsObject? = null
    
    var gravity = 0.5f // Tuned gravity
    var friction = 0.98f
    var bounce = 0.6f // Reduced bounce

    init {
        try {
            toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        } catch (e: Exception) {}
        
        val colors = listOf(Color.Red, Color(0xFF00BCD4), Color(0xFF4CAF50), Color(0xFFFFEB3B), Color(0xFF9C27B0), Color(0xFFFF9800))
        for (i in 0..15) {
            val type = ShapeType.values()[Random.nextInt(ShapeType.values().size)]
            val s = Random.nextFloat() * 40f + 50f
            val sY = if (type == ShapeType.RECT) s * 1.8f else s
            objects.add(
                PhysicsObject(
                    x = Random.nextFloat() * 1500f + 100f,
                    y = Random.nextFloat() * 400f,
                    type = type,
                    size = s,
                    sizeY = sY,
                    color = colors[Random.nextInt(colors.size)]
                )
            )
        }
    }
    
    fun update(pointerX: Float, pointerY: Float, isPointerDown: Boolean) {
        val dt = 1f
        
        if (isPointerDown) {
            if (!isPinching) {
                isPinching = true
                grabbedObject = objects.find { obj -> 
                    val dist = kotlin.math.hypot(obj.x - pointerX, obj.y - pointerY)
                    dist < Math.max(obj.size, obj.sizeY) * 1.5f
                }
            }
        } else {
            isPinching = false
            if (grabbedObject != null) {
                grabbedObject = null
            }
        }
        
        if (grabbedObject != null) {
            val obj = grabbedObject!!
            obj.lastX = obj.x
            obj.lastY = obj.y
            obj.x += (pointerX - obj.x) * 0.4f
            obj.y += (pointerY - obj.y) * 0.4f
            obj.vx = clamp((obj.x - obj.lastX) * 0.8f, -70f, 70f)
            obj.vy = clamp((obj.y - obj.lastY) * 0.8f, -70f, 70f)
        }
        
        for (obj in objects) {
            if (obj == grabbedObject) continue
            
            obj.lastX = obj.x
            obj.lastY = obj.y
            
            obj.vy += gravity
            
            obj.x += obj.vx * dt
            obj.y += obj.vy * dt
            
            // Floor
            if (obj.y + obj.size > height) { // Size represents bottom visually better
                obj.y = height - obj.size
                if (obj.vy > 8f) playBump()
                obj.vy = -obj.vy * bounce
                obj.vx *= friction
            }
            
            // Walls
            if (obj.x - obj.size < 0) {
                obj.x = obj.size
                if (Math.abs(obj.vx) > 8f) playBump()
                obj.vx = -obj.vx * bounce
            }
            if (obj.x + obj.size > width) {
                obj.x = width - obj.size
                if (Math.abs(obj.vx) > 8f) playBump()
                obj.vx = -obj.vx * bounce
            }
        }
        
        // Simple Physics Iterations for circles (easier for casual interactive physics)
        for (iter in 0..1) {
            for (i in 0 until objects.size) {
                for (j in i + 1 until objects.size) {
                    val o1 = objects[i]
                    val o2 = objects[j]
                    
                    val dx = o2.x - o1.x
                    val dy = o2.y - o1.y
                    val dist = kotlin.math.hypot(dx, dy)
                    val minDist = o1.size + o2.size
                    
                    if (dist < minDist && dist > 0.01f) {
                        val overlap = minDist - dist
                        val nx = dx / dist
                        val ny = dy / dist
                        
                        val pushRatio1 = if (o1 == grabbedObject) 0f else 0.5f
                        val pushRatio2 = if (o2 == grabbedObject) 0f else 0.5f
                        
                        if (o1 != grabbedObject) {
                            o1.x -= nx * overlap * pushRatio1
                            o1.y -= ny * overlap * pushRatio1
                        }
                        if (o2 != grabbedObject) {
                            o2.x += nx * overlap * pushRatio2
                            o2.y += ny * overlap * pushRatio2
                        }
                        
                        val dvx = o1.vx - o2.vx
                        val dvy = o1.vy - o2.vy
                        val dot = dvx * nx + dvy * ny
                        
                        if (dot > 0) {
                            if (dot > 10f && iter == 0) playBump()
                            val impulse = dot * bounce
                            if (o1 != grabbedObject) {
                                o1.vx -= impulse * nx
                                o1.vy -= impulse * ny
                            }
                            if (o2 != grabbedObject) {
                                o2.vx += impulse * nx
                                o2.vy += impulse * ny
                            }
                        }
                    }
                }
            }
        }
    }
    
    private fun clamp(v: Float, min: Float, max: Float): Float {
        return Math.max(min, Math.min(max, v))
    }
    
    private var lastBumpTime = 0L
    private fun playBump() {
        val now = System.currentTimeMillis()
        if (now - lastBumpTime > 120) {
            toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, 20)
            lastBumpTime = now
        }
    }

    // DRAWING LOGIC INJECTED FOR COMPOSE
    fun draw(drawScope: DrawScope) {
        val objectsToDraw = objects.sortedBy { it.y } // Simple pseudo depth sorting
        for (obj in objectsToDraw) {
            draw3DObject(drawScope, obj)
        }
    }

    private fun draw3DObject(drawScope: DrawScope, obj: PhysicsObject) {
        val x = obj.x
        val y = obj.y
        val r = obj.size
        
        // Draw shadow
        drawScope.drawOval(
            color = Color(0, 0, 0, 80),
            topLeft = Offset(x - r, y + r * 0.4f), 
            size = Size(r * 2, r * 0.6f)
        )
        
        when (obj.type) {
            ShapeType.CIRCLE -> {
                drawScope.drawCircle(
                    brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(Color.White, obj.color),
                        center = Offset(x - r*0.3f, y - r*0.3f),
                        radius = r * 1.5f
                    ),
                    radius = r,
                    center = Offset(x, y)
                )
            }
            ShapeType.SQUARE -> {
                val topColor = obj.color.copy(alpha = 0.6f)
                val leftColor = obj.color.copy(alpha = 0.8f)
                val rightColor = obj.color
                
                val pathTop = Path().apply {
                    moveTo(x, y - r)
                    lineTo(x + r, y - r/2)
                    lineTo(x, y)
                    lineTo(x - r, y - r/2)
                    close()
                }
                val pathLeft = Path().apply {
                    moveTo(x - r, y - r/2)
                    lineTo(x, y)
                    lineTo(x, y + r)
                    lineTo(x - r, y + r/2)
                    close()
                }
                val pathRight = Path().apply {
                    moveTo(x, y)
                    lineTo(x + r, y - r/2)
                    lineTo(x + r, y + r/2)
                    lineTo(x, y + r)
                    close()
                }
                
                drawScope.drawPath(pathTop, topColor)
                drawScope.drawPath(pathLeft, leftColor)
                drawScope.drawPath(pathRight, rightColor)
            }
            ShapeType.RECT -> {
                val ry = obj.sizeY * 1.5f
                val topColor = obj.color.copy(alpha = 0.6f)
                val leftColor = obj.color.copy(alpha = 0.8f)
                val rightColor = obj.color
                
                val pathTop = Path().apply {
                    moveTo(x, y - ry + r)
                    lineTo(x + r, y - ry + r * 1.5f)
                    lineTo(x, y - ry + r * 2f)
                    lineTo(x - r, y - ry + r * 1.5f)
                    close()
                }
                val pathLeft = Path().apply {
                    moveTo(x - r, y - ry + r * 1.5f)
                    lineTo(x, y - ry + r * 2f)
                    lineTo(x, y + r)
                    lineTo(x - r, y + r/2)
                    close()
                }
                val pathRight = Path().apply {
                    moveTo(x, y - ry + r * 2f)
                    lineTo(x + r, y - ry + r * 1.5f)
                    lineTo(x + r, y + r/2)
                    lineTo(x, y + r)
                    close()
                }
                
                drawScope.drawPath(pathTop, topColor)
                drawScope.drawPath(pathLeft, leftColor)
                drawScope.drawPath(pathRight, rightColor)
            }
        }
    }
}