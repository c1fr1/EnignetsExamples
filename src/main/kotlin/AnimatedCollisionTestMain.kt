import engine.*
import engine.entities.Camera3D
import engine.entities.animations.Animation
import engine.entities.animations.Skeleton
import engine.opengl.*
import engine.opengl.bufferObjects.*
import engine.opengl.jomlExtensions.times
import engine.opengl.shaders.ComputeProgram
import engine.opengl.shaders.ShaderProgram
import engine.opengl.shaders.ShaderType
import engine.shapes.Mesh
import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.assimp.AIMesh
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL11.*
import kotlin.math.min

fun main() {
	EnigContext.init()
	val window = EnigWindow("enignets demo", GLContextPreset.standard3D.extended { glDisable(GL_CULL_FACE) })
	val view = MainView(window)
	view.runInGLSafe(window)

	EnigContext.terminate()
}

class MainView(window : EnigWindow) : EnigView() {

	lateinit var computeShader : ComputeProgram
	lateinit var vertCompShader : ComputeProgram
	lateinit var colorShader : ShaderProgram

	lateinit var humanPosBuffer : SSBO3f
	lateinit var humanNormBuffer : SSBO3f
	lateinit var boneIndexBuffer : SSBO4i
	lateinit var boneWeightBuffer : SSBO4f

	lateinit var swordPosBuf : SSBO3f
	lateinit var swordNormBuf : SSBO3f

	lateinit var outSwordSSBOs : Array<SSBO4f>
	lateinit var outHumanSSBOs : Array<SSBO4f>

	lateinit var sphere : VAO
	lateinit var swordVAOs : Array<VAO>
	lateinit var humanVAOs : Array<VAO>

	lateinit var skeleton : Skeleton
	lateinit var anim : Animation

	val swordMesh = Mesh("dfd7tool.dae", 0)

	val cam = Camera3D(window.aspectRatio)

	val input = window.inputHandler

	var internalFrame = 0f
	val frame : Int get() = (internalFrame * anim.numFrames * 3).toInt()

	var beforeTransform = Matrix4f().scale(0.3f)
	var afterTransform = Matrix4f().scale(0.3f).translate(0.5f, 0f, 0f)

	var collisionT = 1f

	override fun generateResources(window: EnigWindow) {

		super.generateResources(window)

		val scene = loadScene("humanoid.dae")
		val mesh = AIMesh.create(scene.mMeshes()!![0])
		anim = Animation(scene, 0)
		skeleton = Skeleton(scene, 0)

		val boneData = loadBoneData(mesh)

		boneIndexBuffer = SSBO4i(boneData.first)
		boneWeightBuffer = SSBO4f(boneData.second)

		sphere = VAO(loadScene("s peer.dae"), 0)

		val swordAIMesh = AIMesh.create(loadScene("dfd7tool.dae").mMeshes()!![0])
		swordPosBuf = SSBO3f(swordMesh.vdata)
		swordNormBuf = SSBO3f(swordAIMesh.mNormals()!!)

		humanPosBuffer = SSBO3f(mesh.mVertices())
		humanNormBuffer = SSBO3f(mesh.mNormals()!!)

		outSwordSSBOs = Array(6) {SSBO4f(FloatArray(swordPosBuf.vertexCount * 4), true)}
		outHumanSSBOs = Array(6) {SSBO4f(FloatArray(swordPosBuf.vertexCount * 4), true)}

		val swordMeshIBO = IBO(swordMesh.indices)

		swordVAOs = Array(3) {VAO(arrayOf(outSwordSSBOs[it * 2], outSwordSSBOs[it * 2 + 1]), swordMeshIBO)}
		humanVAOs = Array(3) {VAO(arrayOf(outHumanSSBOs[it * 2], outHumanSSBOs[it * 2 + 1]), IBO(mesh.mFaces()))}

		computeShader = ComputeProgram("res/shaders/animComputeShader/compute.glsl")
		vertCompShader = ComputeProgram("res/shaders/vertComputeShader/compute.glsl")
		colorShader = ShaderProgram("ssboColorShader")

		cam.z = 5f

		beforeTransform = Matrix4f().translate(cam).rotateY(-cam.angles.y).rotateX(-cam.angles.x - PIf/2).scale(0.3f)
		afterTransform = Matrix4f().translate(cam).rotateY(-cam.angles.y).rotateX(-cam.angles.x - PIf/2).scale(0.3f)

		window.inputEnabled = true
	}

	override fun loop(frameBirth: Long, dtime: Float) : Boolean {
		FBO.prepareDefaultRender()

		collisionT = calculateCollision()

		val proportionT = min(internalFrame, collisionT)
		val beforeMats = skeleton.getMats(anim,  0)
		val afterMats = skeleton.getMats(anim,  anim.numFrames - 1)

		internalFrame = (internalFrame + dtime) % 1f

		computeAnimationPos(outHumanSSBOs[0], outHumanSSBOs[1], beforeMats)
		computeAnimationPos(outHumanSSBOs[2], outHumanSSBOs[3], afterMats)
		computeAnimationPos(outHumanSSBOs[4], outHumanSSBOs[5], beforeMats.mapIndexed {i, before ->
			before.lerp(afterMats[i], proportionT)}.toTypedArray())
		computeSwordPos(outSwordSSBOs[0], outSwordSSBOs[1], beforeTransform)
		computeSwordPos(outSwordSSBOs[2], outSwordSSBOs[3], afterTransform)
		computeSwordPos(outSwordSSBOs[4], outSwordSSBOs[5], beforeTransform.lerp(afterTransform, proportionT, Matrix4f()))

		if (input.mouseButtons[GLFW_MOUSE_BUTTON_LEFT].isDown) {
			beforeTransform = Matrix4f().translate(cam).rotateY(-cam.angles.y).rotateX(-cam.angles.x - PIf/2).scale(0.3f)
		}
		if (input.mouseButtons[GLFW_MOUSE_BUTTON_RIGHT].isDown) {
			afterTransform = Matrix4f().translate(cam).rotateY(-cam.angles.y).rotateX(-cam.angles.x - PIf/2).scale(0.3f)
		}

		SSBO.syncSSBOs()

		renderVAO()

		handleMovement(dtime)

		return input.keys[GLFW_KEY_ESCAPE].isDown
	}

	fun calculateCollision() : Float {
		SSBO.syncSSBOs()

		fun FloatArray.excludew() : FloatArray {
			return FloatArray(this.size * 3 / 4) { get((it / 3) * 4 + (it % 3)) }
		}

		val beforeToolBuffer = outSwordSSBOs[0].retrieveGLData().excludew()
		val beforeHumanoidBuffer = outHumanSSBOs[0].retrieveGLData().excludew()

		val afterToolBuffer = outSwordSSBOs[2].retrieveGLData().excludew()
		val afterHumanoidBuffer = outHumanSSBOs[2].retrieveGLData().excludew()

		val beforeToolMesh = Mesh(swordVAOs[0].ibo.data, beforeToolBuffer)
		val beforeHumanoidMesh = Mesh(humanVAOs[0].ibo.data, beforeHumanoidBuffer)
		return beforeToolMesh.intersectionT(beforeHumanoidMesh, afterToolBuffer, afterHumanoidBuffer) ?: 1f
	}

	fun renderVAO() {
		colorShader.enable()

		colorShader[ShaderType.VERTEX_SHADER, 0] = cam.getMatrix()
		colorShader[ShaderType.VERTEX_SHADER, 1] = cam.normalize(Vector3f())
		colorShader[ShaderType.FRAGMENT_SHADER, 0] = Vector3f(1f, 1f, 1f)

		for (svao in swordVAOs) {
			svao.fullRender()
		}
		for (hvao in humanVAOs) {
			hvao.fullRender()
		}
		sphere.prepareRender()

		val mats = skeleton.getAnimMats(anim, frame / 3)

		for (m in mats) {

			colorShader[ShaderType.FRAGMENT_SHADER, 0] = Vector3f(0f, 1f, 0f)
			colorShader[ShaderType.VERTEX_SHADER, 0] = (cam.getMatrix() * m).scale(0.03f)
			sphere.drawTriangles()
		}
		sphere.unbind()
	}

	fun computeAnimationPos(oPosBuffer : SSBO4f, oNormBuffer : SSBO4f, mats : Array<Matrix4f>) {

		//frame = (frame + 1) % (anim.numFrames * 3)

		computeShader.enable()
		humanPosBuffer.bindToPosition(0)
		humanNormBuffer.bindToPosition(1)
		boneIndexBuffer.bindToPosition(2)
		boneWeightBuffer.bindToPosition(3)

		oPosBuffer.bindToPosition(4)
		oNormBuffer.bindToPosition(5)

		computeShader[0] = Matrix4f()
		//val mats = skeleton.getMats(anim,  min(frame / 3, (collisionT * anim.numFrames).toInt()))

		computeShader[1] = mats

		computeShader.run(humanPosBuffer.vertexCount)
	}

	fun computeSwordPos(oPosBuffer : SSBO4f, oNormBuffer : SSBO4f, mat : Matrix4f) {
		vertCompShader.enable()
		vertCompShader[0] = mat
		swordPosBuf.bindToPosition(0)
		swordNormBuf.bindToPosition(1)

		oPosBuffer.bindToPosition(2)
		oNormBuffer.bindToPosition(3)

		computeShader.run(humanPosBuffer.vertexCount)
	}

	private fun handleMovement(dtime : Float) {

		val ms = if (input.keys[GLFW_KEY_LEFT_CONTROL].isDown) 0.5f else 2f

		if (input.keys[GLFW_KEY_SPACE].isDown) cam.y += dtime * ms
		if (input.keys[GLFW_KEY_LEFT_SHIFT].isDown) cam.y -= dtime * ms
		if (input.keys[GLFW_KEY_D].isDown) cam.moveRelativeRight(dtime * ms)
		if (input.keys[GLFW_KEY_A].isDown) cam.moveRelativeLeft(dtime * ms)
		if (input.keys[GLFW_KEY_W].isDown) cam.moveRelativeForward(dtime * ms)
		if (input.keys[GLFW_KEY_S].isDown) cam.moveRelativeBackward(dtime * ms)
		cam.updateAngles(input, dtime / 10f)
		cam.angles.x = cam.angles.x.coerceIn(-PIf / 2, PIf / 2)
	}
}