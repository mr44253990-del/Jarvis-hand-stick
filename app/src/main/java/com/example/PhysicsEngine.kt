package com.example

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.hypot
import kotlin.random.Random

enum class ShapeType { CIRCLE, SQUARE, DIAMOND }

class PhysicsObject(
    var x: Float,
    var y: Float,
    var z: Float,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var vz: Float = 0f,
    val type: ShapeType,
    val size: Float,
    var color: Color,
    val isTarget: Boolean = false,
    val label: String = "",
    val startX: Float = x,
    val startY: Float = y,
    val startZ: Float = z
) {
    var lastX: Float = x
    var lastY: Float = y
    var lastZ: Float = z
    var hasScored: Boolean = false
    var weight: Float = if (type == ShapeType.CIRCLE && !isTarget) 1.8f else 0.8f // Throwing spheres are heavier
}

class PhysicsEngine {
    val objects = mutableListOf<PhysicsObject>()
    private var toneGen: ToneGenerator? = null
    
    var width: Float = 1f
    var height: Float = 1f
    val maxDepth = 1000f
    
    var isPinching: Boolean = false
    var grabbedObject: PhysicsObject? = null
    
    var gravity = 0.35f
    var friction = 0.985f
    var bounce = 0.55f

    var score = 0
    var combo = 1
    var lastTargetHitTime = 0L
    var isInitialized = false

    init {
        try {
            toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        } catch (e: Exception) {}
    }
    
    private fun clamp(v: Float, min: Float, max: Float): Float = Math.max(min, Math.min(max, v))
    
    fun resetGame() {
        objects.clear()
        score = 0
        combo = 1
        val cx = if (width > 1) width / 2f else 540f
        val cy = if (height > 1) height / 2f else 960f
        val baseFloorY = height * 0.82f
        
        // Target colors
        val colors = listOf(
            Color(0xFFFF0055), // Red/Pink
            Color(0xFF00FF00), // Green
            Color(0xFFAA00FF), // Neon Purple
            Color(0xFFFF8800)  // Bright Orange
        )

        // 1. Pyramid on Platform 1 (Back Left: z = 800f, x = cx - 280f)
        createPyramid(cx - 300f, baseFloorY, 800f, heightRows = 3, size = 30f, color = colors[0])

        // 2. Pyramid on Platform 2 (Back Right: z = 600f, x = cx + 320f)
        createPyramid(cx + 320f, baseFloorY, 600f, heightRows = 3, size = 30f, color = colors[1])

        // 3. Stacks on Platform 3 (Mid Left: z = 400f, x = cx - 380f)
        createPyramid(cx - 380f, baseFloorY, 400f, heightRows = 2, size = 35f, color = colors[2])

        // 4. Stacks on Platform 4 (Near Right: z = 200f, x = cx + 180f)
        createPyramid(cx + 180f, baseFloorY, 200f, heightRows = 2, size = 35f, color = colors[3])

        // 5. High-Value Diamond Floating Targets with distance tags like the image
        objects.add(PhysicsObject(cx - 220f, cy - 250f, 750f, type = ShapeType.DIAMOND, size = 25f, color = Color(0xFF00E5FF), isTarget = true, label = "28m"))
        objects.add(PhysicsObject(cx, cy - 320f, 900f, type = ShapeType.DIAMOND, size = 30f, color = Color(0xFFFFCC00), isTarget = true, label = "18m"))
        objects.add(PhysicsObject(cx + 200f, cy - 200f, 500f, type = ShapeType.DIAMOND, size = 22f, color = Color(0xFF00FF66), isTarget = true, label = "35m"))
        objects.add(PhysicsObject(cx + 420f, cy - 280f, 850f, type = ShapeType.DIAMOND, size = 26f, color = Color(0xFFFF5500), isTarget = true, label = "42m"))

        // 6. Glowing Cyan Throwing Spheres in the foreground (close proximity to human hand screen space)
        for (i in 0..2) {
            objects.add(
                PhysicsObject(
                    x = cx - 180f + i * 180f,
                    y = baseFloorY - 120f,
                    z = 80f,
                    type = ShapeType.CIRCLE,
                    size = 42f,
                    color = Color(0xFF00FFFF),
                    isTarget = false
                )
            )
        }
    }

    private fun createPyramid(centerX: Float, baseFloorY: Float, z: Float, heightRows: Int, size: Float, color: Color) {
        val totalSize = size * 2f
        val spacing = 3f
        for (row in 0 until heightRows) {
            val cubesInRow = heightRows - row
            val startX = centerX - (cubesInRow - 1) * (totalSize + spacing) / 2f
            val y = baseFloorY - (row * (totalSize + spacing)) - size
            for (col in 0 until cubesInRow) {
                val x = startX + col * (totalSize + spacing)
                objects.add(
                    PhysicsObject(
                        x = x,
                        y = y,
                        z = z,
                        type = ShapeType.SQUARE,
                        size = size,
                        color = color,
                        isTarget = true
                    )
                )
            }
        }
    }
    
    fun update(pointerX: Float, pointerY: Float, pointerZ: Float, isPointerDown: Boolean) {
        val dt = 1f

        if (!isInitialized && width > 100 && height > 100) {
            resetGame()
            isInitialized = true
        }
        
        val fov = 800f
        val cx = width / 2f
        val cy = height / 2f

        // Grabbing algorithm optimized in 2D Screen Projected Coordinates:
        if (isPointerDown) {
            if (!isPinching) {
                isPinching = true
                
                // Find closest object in 2D Projected Screen Coordinate!
                var bestObj: PhysicsObject? = null
                var bestDist2D = Float.MAX_VALUE
                
                for (obj in objects) {
                    val scale = fov / (fov + obj.z)
                    val px = (obj.x - cx) * scale + cx
                    val py = (obj.y - cy) * scale + cy
                    val dist2D = hypot(px - pointerX, py - pointerY)
                    
                    // We can grab any object as long as our hand is visually close to it on the screen!
                    val projectedSize = obj.size * scale
                    val grabLimit = Math.max(projectedSize * 4.0f, 180f) // Very robust, warm, forgiveness zone
                    if (dist2D < grabLimit && dist2D < bestDist2D) {
                        bestDist2D = dist2D
                        bestObj = obj
                    }
                }
                
                if (bestObj != null) {
                    grabbedObject = bestObj
                    // Play a cool cyberpunk laser grab tone
                    try {
                        toneGen?.startTone(ToneGenerator.TONE_PROP_ACK, 25)
                    } catch (e: Exception) {}
                }
            }
        } else {
            isPinching = false
            if (grabbedObject != null) {
                // When flinging, apply a solid multiplying force for extremely satisfying throw experience!
                val go = grabbedObject!!
                go.vx *= 1.8f
                go.vy *= 1.8f
                go.vz *= 2.3f
                grabbedObject = null
            }
        }
        
        // Drag active object directly under user's fingers
        if (grabbedObject != null) {
            val obj = grabbedObject!!
            obj.lastX = obj.x
            obj.lastY = obj.y
            obj.lastZ = obj.z
            
            // Map 2D pointer back to 3D based on target coordinate projections
            val scale = fov / (fov + pointerZ)
            val tx = cx + (pointerX - cx) / scale
            val ty = cy + (pointerY - cy) / scale
            
            obj.x += (tx - obj.x) * 0.45f
            obj.y += (ty - obj.y) * 0.45f
            obj.z += (pointerZ - obj.z) * 0.45f
            
            obj.vx = clamp(obj.x - obj.lastX, -60f, 60f)
            obj.vy = clamp(obj.y - obj.lastY, -60f, 60f)
            obj.vz = clamp(obj.z - obj.lastZ, -60f, 60f)
            
            // Releasing high velocity when moving can score points
            obj.hasScored = false
        }
        
        val floorY = height * 0.82f 
        
        for (obj in objects) {
            if (obj == grabbedObject) continue
            
            obj.lastX = obj.x
            obj.lastY = obj.y
            obj.lastZ = obj.z
            
            // Gravity is weaker for lighter targets to let them fly elegantly!
            val currentGravity = if (obj.isTarget) gravity * 0.75f else gravity
            obj.vy += currentGravity
            
            obj.x += obj.vx * dt
            obj.y += obj.vy * dt
            obj.z += obj.vz * dt
            
            // Check if targets have moved or fallen from their original stack location to score!
            if (obj.isTarget && !obj.hasScored) {
                val distFromStart = hypot(obj.x - obj.startX, obj.y - obj.startY)
                // If it fell or got knocked away
                if (distFromStart > 45f) {
                    obj.hasScored = true
                    
                    val now = System.currentTimeMillis()
                    if (now - lastTargetHitTime < 2500) {
                        combo = Math.min(combo + 1, 15)
                    } else {
                        combo = 1
                    }
                    lastTargetHitTime = now
                    
                    // Add score
                    val award = if (obj.type == ShapeType.DIAMOND) 2500 else 1000
                    score += award * combo
                    
                    // Score notification tone
                    try {
                        toneGen?.startTone(ToneGenerator.TONE_DTMF_D, 35)
                    } catch (e: Exception) {}
                }
            }
            
            // floor collision
            if (obj.y + obj.size > floorY) {
                obj.y = floorY - obj.size
                if (Math.abs(obj.vy) > 4f) {
                    playBump()
                }
                obj.vy = -obj.vy * bounce
                obj.vx *= friction
                obj.vz *= friction
            }
            
            // Ceiling boundary
            if (obj.y - obj.size < 0) {
                obj.y = obj.size
                obj.vy = -obj.vy * bounce
            }
            
            // Walls left / right
            if (obj.x - obj.size < 0) {
                obj.x = obj.size
                if (Math.abs(obj.vx) > 4f) playBump()
                obj.vx = -obj.vx * bounce
            }
            if (obj.x + obj.size > width) {
                obj.x = width - obj.size
                if (Math.abs(obj.vx) > 4f) playBump()
                obj.vx = -obj.vx * bounce
            }
            
            // Front / back walls
            if (obj.z < 0) {
                obj.z = 2f
                obj.vz = -obj.vz * bounce
            }
            if (obj.z > maxDepth) {
                obj.z = maxDepth - 2f
                obj.vz = -obj.vz * bounce
            }
        }
        
        // 3D elastic collisions between multiple objects
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
                        
                        // Relative velocities
                        val dvx = o1.vx - o2.vx
                        val dvy = o1.vy - o2.vy
                        val dvz = o1.vz - o2.vz
                        val dot = dvx * nx + dvy * ny + dvz * nz
                        
                        if (dot > 0) {
                            if (dot > 4f && iter == 0) playBump()
                            
                            val impulse = dot * (bounce + 0.1f)
                            
                            if (o1 != grabbedObject) {
                                o1.vx -= impulse * nx * (1.0f / o1.weight)
                                o1.vy -= impulse * ny * (1.0f / o1.weight)
                                o1.vz -= impulse * nz * (1.0f / o1.weight)
                            }
                            if (o2 != grabbedObject) {
                                o2.vx += impulse * nx * (1.0f / o2.weight)
                                o2.vy += impulse * ny * (1.0f / o2.weight)
                                o2.vz += impulse * nz * (1.0f / o2.weight)
                            }
                        }
                    }
                }
            }
        }
        
        // Auto-Regenerate throwing cyan balls in foreground if they are thrown away (low Z count)
        val nearBallsCount = objects.count { it.type == ShapeType.CIRCLE && !it.isTarget && it.z < 250f }
        if (nearBallsCount < 2) {
            val baseFloorY = height * 0.82f
            objects.add(
                PhysicsObject(
                    x = cx + Random.nextFloat() * 300f - 150f,
                    y = baseFloorY - 100f,
                    z = 70f,
                    type = ShapeType.CIRCLE,
                    size = 42f,
                    color = Color(0xFF00FFFF),
                    isTarget = false
                )
            )
        }
    }
    
    private var lastBumpTime = 0L
    private fun playBump() {
        val now = System.currentTimeMillis()
        if (now - lastBumpTime > 150) {
            try {
                // Crisper scifi metal bounce beep
                toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP2, 12)
            } catch (e: Exception) {}
            lastBumpTime = now
        }
    }

    fun draw(drawScope: DrawScope) {
        val fov = 800f
        val cx = width / 2f
        val cy = height / 2f

        // Draw Interactive Platforms (styled exactly like the image)
        val platformZPositions = listOf(800f, 600f, 400f, 200f)
        val platformXOffsets = listOf(-300f, 320f, -380f, 180f)
        platformZPositions.forEachIndexed { i, pZ ->
            val pX = cx + platformXOffsets[i]
            val pY = height * 0.82f
            val scale = fov / (fov + pZ)
            val px = (pX - cx) * scale + cx
            val py = (pY - cy) * scale + cy
            val pR = 140f * scale
            
            // Outer cyan glowing base
            drawScope.drawOval(
                color = Color(0x2200E5FF),
                topLeft = Offset(px - pR, py - pR * 0.35f),
                size = Size(pR * 2, pR * 0.7f)
            )
            // Inner cyan ring light
            drawScope.drawOval(
                color = Color(0xFF00FFFF).copy(alpha = 0.6f),
                topLeft = Offset(px - pR * 0.8f, py - pR * 0.28f),
                size = Size(pR * 1.6f, pR * 0.56f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5f * scale)
            )
        }

        // Draw objects sorted by Z (far to near)
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
        
        // Floor shadow
        val floorYProj = (height * 0.82f - cy) * scale + cy
        val shadowDist = (floorYProj - py) / (height * scale)
        val shadowAlpha = clamp(1f - shadowDist * 3f, 0f, 0.75f)
        drawScope.drawOval(
            color = Color.Black.copy(alpha = shadowAlpha),
            topLeft = Offset(px - pr, floorYProj - pr * 0.2f), 
            size = Size(pr * 2, pr * 0.4f)
        )
        
        // Glow layer matching image style
        drawScope.drawCircle(
            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                colors = listOf(obj.color.copy(alpha = 0.5f), Color.Transparent),
                center = Offset(px, py),
                radius = pr * 2.8f
            ),
            radius = pr * 2.8f,
            center = Offset(px, py)
        )
        
        when (obj.type) {
            ShapeType.CIRCLE -> { 
                // Glowing Sphere (throwing balls)
                drawScope.drawCircle(
                    brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(Color.White, obj.color, obj.color.copy(alpha = 0.4f)),
                        center = Offset(px - pr*0.25f, py - pr*0.25f),
                        radius = pr * 1.1f
                    ),
                    radius = pr,
                    center = Offset(px, py)
                )
                // Highlights
                drawScope.drawCircle(
                    color = Color.White.copy(alpha = 0.8f),
                    radius = pr * 0.15f,
                    center = Offset(px - pr * 0.35f, py - pr * 0.35f)
                )
            }
            ShapeType.SQUARE -> { 
                // Glowing Cyber Cube Shape
                val rX = pr * 0.85f
                val rY = pr * 0.5f 
                
                val topColor = obj.color.copy(alpha = 0.6f)
                val leftColor = obj.color.copy(alpha = 0.85f)
                val rightColor = obj.color
                val edgeColor = if (obj.hasScored) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.82f)
                
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
                
                // Draw illuminated edges
                drawScope.drawPath(pathTop, edgeColor, style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f * scale))
                drawScope.drawPath(pathLeft, edgeColor, style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f * scale))
                drawScope.drawPath(pathRight, edgeColor, style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f * scale))
            }
            ShapeType.DIAMOND -> {
                // Diamond Targets (similar to the image)
                val topColor = obj.color.copy(alpha = 0.5f)
                val bottomColor = obj.color
                val borderCol = Color.White
                
                val pDiamond = Path().apply {
                    moveTo(px, py - pr * 1.5f)
                    lineTo(px + pr * 1.1f, py)
                    lineTo(px, py + pr * 1.5f)
                    lineTo(px - pr * 1.1f, py)
                    close()
                }
                
                drawScope.drawPath(pDiamond, bottomColor)
                drawScope.drawPath(pDiamond, borderCol, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.5f * scale))
                
                // Draw inner sparkling core
                drawScope.drawCircle(
                    color = Color.White,
                    radius = pr * 0.35f,
                    center = Offset(px, py)
                )
            }
        }
    }
}
