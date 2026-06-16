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
    var z: Float,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var vz: Float = 0f,
    val type: ShapeType,
    val size: Float,
    val sizeY: Float = size,
    val color: Color
) {
    var lastX: Float = x
    var lastY: Float = y
    var lastZ: Float = z
}

class PhysicsEngine {
    val objects = mutableListOf<PhysicsObject>()
    private var toneGen: ToneGenerator? = null
    
    var width: Float = 1f
    var height: Float = 1f
    val maxDepth = 1000f
    
    var isPinching: Boolean = false
    var grabbedObject: PhysicsObject? = null
    
    var gravity = 0.4f
    var friction = 0.98f
    var bounce = 0.6f

    init {
        try {
            toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        } catch (e: Exception) {}
        
        // Neon Colors
        val colors = listOf(Color(0xFFFF0055), Color(0xFF00FFFF), Color(0xFF00FF00), Color(0xFFFFFF00), Color(0xFFAA00FF), Color(0xFFFF8800))
        for (i in 0..15) {
            val type = if (Random.nextBoolean()) ShapeType.CIRCLE else ShapeType.SQUARE
            val s = Random.nextFloat() * 30f + 40f
            objects.add(
                PhysicsObject(
                    x = Random.nextFloat() * 1500f + 100f,
                    y = Random.nextFloat() * 400f,
                    z = Random.nextFloat() * maxDepth * 0.8f,
                    type = type,
                    size = s,
                    color = colors[Random.nextInt(colors.size)]
                )
            )
        }
    }
    
    private fun clamp(v: Float, min: Float, max: Float): Float = Math.max(min, Math.min(max, v))
    
    fun update(pointerX: Float, pointerY: Float, pointerZ: Float, isPointerDown: Boolean) {
        val dt = 1f
        
        if (isPointerDown) {
            if (!isPinching) {
                isPinching = true
                grabbedObject = objects.minByOrNull { obj -> 
                    // 3D Distance for grabbing
                    val dx = obj.x - pointerX
                    val dy = obj.y - pointerY
                    val dz = obj.z - pointerZ
                    Math.sqrt((dx*dx + dy*dy + dz*dz).toDouble()).toFloat()
                }?.takeIf { obj -> 
                    val dx = obj.x - pointerX
                    val dy = obj.y - pointerY
                    val dz = obj.z - pointerZ
                    val dist = Math.sqrt((dx*dx + dy*dy + dz*dz).toDouble()).toFloat()
                    dist < Math.max(obj.size, obj.sizeY) * 3f // Grab forgiveness radius
                }
            }
        } else {
            isPinching = false
            if (grabbedObject != null) grabbedObject = null
        }
        
        if (grabbedObject != null) {
            val obj = grabbedObject!!
            obj.lastX = obj.x
            obj.lastY = obj.y
            obj.lastZ = obj.z
            obj.x += (pointerX - obj.x) * 0.4f
            obj.y += (pointerY - obj.y) * 0.4f
            obj.z += (pointerZ - obj.z) * 0.4f
            obj.vx = clamp((obj.x - obj.lastX) * 0.8f, -70f, 70f)
            obj.vy = clamp((obj.y - obj.lastY) * 0.8f, -70f, 70f)
            obj.vz = clamp((obj.z - obj.lastZ) * 0.8f, -70f, 70f)
        }
        
        val floorY = height * 0.85f 
        
        for (obj in objects) {
            if (obj == grabbedObject) continue
            
            obj.lastX = obj.x
            obj.lastY = obj.y
            obj.lastZ = obj.z
            
            obj.vy += gravity
            
            obj.x += obj.vx * dt
            obj.y += obj.vy * dt
            obj.z += obj.vz * dt
            
            // Floor
            if (obj.y + obj.size > floorY) {
                obj.y = floorY - obj.size
                if (obj.vy > 8f) playBump()
                obj.vy = -obj.vy * bounce
                obj.vx *= friction
                obj.vz *= friction
            }
            
            // Ceiling
            if (obj.y - obj.size < 0) {
                obj.y = obj.size
                obj.vy = -obj.vy * bounce
            }
            
            // Walls in X
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
            
            // Walls in Z
            if (obj.z < 0) {
                obj.z = 0f
                obj.vz = -obj.vz * bounce
            }
            if (obj.z > maxDepth) {
                obj.z = maxDepth
                obj.vz = -obj.vz * bounce
            }
        }
        
        // Sphere 3D collision
        for (iter in 0..1) {
            for (i in 0 until objects.size) {
                for (j in i + 1 until objects.size) {
                    val o1 = objects[i]
                    val o2 = objects[j]
                    
                    val dx = o2.x - o1.x
                    val dy = o2.y - o1.y
                    val dz = o2.z - o1.z
                    val dist = Math.sqrt((dx*dx + dy*dy + dz*dz).toDouble()).toFloat()
                    val minDist = o1.size + o2.size
                    
                    if (dist < minDist && dist > 0.01f) {
                        val overlap = minDist - dist
                        val nx = dx / dist
                        val ny = dy / dist
                        val nz = dz / dist
                        
                        val pushRatio1 = if (o1 == grabbedObject) 0f else 0.5f
                        val pushRatio2 = if (o2 == grabbedObject) 0f else 0.5f
                        
                        if (o1 != grabbedObject) {
                            o1.x -= nx * overlap * pushRatio1
                            o1.y -= ny * overlap * pushRatio1
                            o1.z -= nz * overlap * pushRatio1
                        }
                        if (o2 != grabbedObject) {
                            o2.x += nx * overlap * pushRatio2
                            o2.y += ny * overlap * pushRatio2
                            o2.z += nz * overlap * pushRatio2
                        }
                        
                        val dvx = o1.vx - o2.vx
                        val dvy = o1.vy - o2.vy
                        val dvz = o1.vz - o2.vz
                        val dot = dvx * nx + dvy * ny + dvz * nz
                        
                        if (dot > 0) {
                            if (dot > 10f && iter == 0) playBump()
                            val impulse = dot * bounce
                            if (o1 != grabbedObject) {
                                o1.vx -= impulse * nx
                                o1.vy -= impulse * ny
                                o1.vz -= impulse * nz
                            }
                            if (o2 != grabbedObject) {
                                o2.vx += impulse * nx
                                o2.vy += impulse * ny
                                o2.vz += impulse * nz
                            }
                        }
                    }
                }
            }
        }
    }
    
    private var lastBumpTime = 0L
    private fun playBump() {
        val now = System.currentTimeMillis()
        if (now - lastBumpTime > 120) {
            toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP2, 15)
            lastBumpTime = now
        }
    }

    fun draw(drawScope: DrawScope) {
        val fov = 800f
        val cx = width / 2f
        val cy = height / 2f

        // Draw Platforms
        val platformZPositions = listOf(800f, 600f, 400f, 200f)
        val platformXOffsets = listOf(-200f, 300f, -400f, 100f)
        platformZPositions.forEachIndexed { i, pZ ->
            val pX = cx + platformXOffsets[i]
            val pY = height * 0.85f
            val scale = fov / (fov + pZ)
            val px = (pX - cx) * scale + cx
            val py = (pY - cy) * scale + cy
            val pR = 150f * scale
            drawScope.drawOval(
                color = Color(0x3300FFFF),
                topLeft = Offset(px - pR, py - pR * 0.3f),
                size = Size(pR * 2, pR * 0.6f)
            )
            drawScope.drawOval(
                color = Color(0xFF00FFFF).copy(alpha = 0.5f),
                topLeft = Offset(px - pR*0.8f, py - pR * 0.24f),
                size = Size(pR * 1.6f, pR * 0.48f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f * scale)
            )
        }

        // Sort by Z descending (far to near)
        val objectsToDraw = objects.sortedByDescending { it.z }

        for (obj in objectsToDraw) {
            draw3DObject(drawScope, obj, fov, cx, cy)
        }
    }

    private fun draw3DObject(drawScope: DrawScope, obj: PhysicsObject, fov: Float, cx: Float, cy: Float) {
        val scale = fov / (fov + obj.z)
        if (scale <= 0) return
        
        val px = (obj.x - cx) * scale + cx
        val py = (obj.y - cy) * scale + cy
        val pr = obj.size * scale
        
        val floorYProj = (height * 0.85f - cy) * scale + cy
        val shadowDist = (floorYProj - py) / (height * scale)
        val shadowAlpha = clamp(1f - shadowDist * 3f, 0f, 0.8f)
        drawScope.drawOval(
            color = Color.Black.copy(alpha = shadowAlpha),
            topLeft = Offset(px - pr, floorYProj - pr * 0.2f), 
            size = Size(pr * 2, pr * 0.4f)
        )
        
        drawScope.drawCircle(
            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                colors = listOf(obj.color.copy(alpha=0.6f), Color.Transparent),
                center = Offset(px, py),
                radius = pr * 2.5f
            ),
            radius = pr * 2.5f,
            center = Offset(px, py)
        )
        
        when (obj.type) {
            ShapeType.CIRCLE -> { 
                drawScope.drawCircle(
                    brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(Color.White, obj.color, obj.color.copy(alpha = 0.5f)),
                        center = Offset(px - pr*0.3f, py - pr*0.3f),
                        radius = pr * 1.2f
                    ),
                    radius = pr,
                    center = Offset(px, py)
                )
            }
            ShapeType.SQUARE, ShapeType.RECT -> { 
                val rX = pr * 0.85f
                val rY = pr * 0.5f 
                
                val topColor = obj.color.copy(alpha = 0.6f)
                val leftColor = obj.color.copy(alpha = 0.9f)
                val rightColor = obj.color
                val edgeColor = Color.White.copy(alpha = 0.8f)
                
                val pt0 = Offset(px, py - rY - pr)
                val pt1 = Offset(px + rX, py - pr)
                val pt2 = Offset(px, py + rY - pr)
                val pt3 = Offset(px - rX, py - pr)
                val pt4 = Offset(px + rX, py)
                val pt5 = Offset(px, py + rY)
                val pt6 = Offset(px - rX, py)
                
                val pathTop = Path().apply { moveTo(pt0.x, pt0.y); lineTo(pt1.x, pt1.y); lineTo(pt2.x, pt2.y); lineTo(pt3.x, pt3.y); close() }
                val pathLeft = Path().apply { moveTo(pt3.x, pt3.y); lineTo(pt2.x, pt2.y); lineTo(pt5.x, pt5.y); lineTo(pt6.x, pt6.y); close() }
                val pathRight = Path().apply { moveTo(pt2.x, pt2.y); lineTo(pt1.x, pt1.y); lineTo(pt4.x, pt4.y); lineTo(pt5.x, pt5.y); close() }
                
                drawScope.drawPath(pathTop, topColor)
                drawScope.drawPath(pathLeft, leftColor)
                drawScope.drawPath(pathRight, rightColor)
                
                drawScope.drawPath(pathTop, edgeColor, style = androidx.compose.ui.graphics.drawscope.Stroke(2f * scale))
                drawScope.drawPath(pathLeft, edgeColor, style = androidx.compose.ui.graphics.drawscope.Stroke(2f * scale))
                drawScope.drawPath(pathRight, edgeColor, style = androidx.compose.ui.graphics.drawscope.Stroke(2f * scale))
            }
        }
    }
}
