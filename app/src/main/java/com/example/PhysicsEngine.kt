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
        
        // Neon Colors
        val colors = listOf(Color(0xFFFF0055), Color(0xFF00FFFF), Color(0xFF00FF00), Color(0xFFFFFF00), Color(0xFFAA00FF), Color(0xFFFF8800))
        for (i in 0..18) {
            val type = if (Random.nextBoolean()) ShapeType.CIRCLE else ShapeType.SQUARE
            val s = Random.nextFloat() * 40f + 50f
            val sY = s
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
                    dist < Math.max(obj.size, obj.sizeY) * 2.0f // Increased grab radius
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
            // TONE_PROP_BEEP2 is shorter, crisper beep that fits synth/sci-fi better
            toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP2, 15)
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
        
        // Draw shadow on the floor (fake perspective)
        val shadowDist = (height - y) / height // 0 at bottom, 1 at top
        val shadowAlpha = clamp(1f - shadowDist * 2f, 0f, 0.6f)
        drawScope.drawOval(
            color = Color.Black.copy(alpha = shadowAlpha),
            topLeft = Offset(x - r, height - r * 0.4f), 
            size = Size(r * 2, r * 0.4f)
        )
        
        // Sphere or Cube Outer Glow
        drawScope.drawCircle(
            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                colors = listOf(obj.color.copy(alpha=0.6f), Color.Transparent),
                center = Offset(x, y - r/2f),
                radius = r * 2.5f
            ),
            radius = r * 2.5f,
            center = Offset(x, y - r/2f)
        )
        
        when (obj.type) {
            ShapeType.CIRCLE -> { // Cyber Sphere
                drawScope.drawCircle(
                    brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(Color.White, obj.color, obj.color.copy(alpha = 0.5f)),
                        center = Offset(x - r*0.3f, y - r*0.3f),
                        radius = r * 1.2f
                    ),
                    radius = r,
                    center = Offset(x, y)
                )
            }
            ShapeType.SQUARE -> { // Cyber Cube
                val rX = r * 0.85f
                val rY = r * 0.5f // perspective depth
                
                val topColor = obj.color.copy(alpha = 0.6f)
                val leftColor = obj.color.copy(alpha = 0.9f)
                val rightColor = obj.color
                val edgeColor = Color.White.copy(alpha = 0.8f)
                
                val pt0 = Offset(x, y - rY - r)
                val pt1 = Offset(x + rX, y - r)
                val pt2 = Offset(x, y + rY - r)
                val pt3 = Offset(x - rX, y - r)
                val pt4 = Offset(x + rX, y)
                val pt5 = Offset(x, y + rY)
                val pt6 = Offset(x - rX, y)
                
                val pathTop = Path().apply { moveTo(pt0.x, pt0.y); lineTo(pt1.x, pt1.y); lineTo(pt2.x, pt2.y); lineTo(pt3.x, pt3.y); close() }
                val pathLeft = Path().apply { moveTo(pt3.x, pt3.y); lineTo(pt2.x, pt2.y); lineTo(pt5.x, pt5.y); lineTo(pt6.x, pt6.y); close() }
                val pathRight = Path().apply { moveTo(pt2.x, pt2.y); lineTo(pt1.x, pt1.y); lineTo(pt4.x, pt4.y); lineTo(pt5.x, pt5.y); close() }
                
                drawScope.drawPath(pathTop, topColor)
                drawScope.drawPath(pathLeft, leftColor)
                drawScope.drawPath(pathRight, rightColor)
                
                // Bright edges
                drawScope.drawPath(pathTop, edgeColor, style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
                drawScope.drawPath(pathLeft, edgeColor, style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
                drawScope.drawPath(pathRight, edgeColor, style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
            }
            else -> {}
        }
    }
}