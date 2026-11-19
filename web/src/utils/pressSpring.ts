/**
 * 按钮按压回弹。
 *
 * 用指针事件在 document 上做一次委托，所有按钮（含 Element Plus 动态渲染出来的）
 * 都自动生效，不需要在每个组件里挂指令。
 *
 * 按下时把目标缩放设成 0.97，松手设回 1，位置由弹簧积分算出来：
 *   a = -k (x - target) - c v
 * 刚度 260、阻尼 12，阻尼比约 0.37，属于欠阻尼，回弹时会略微过冲再稳定。
 */

const STIFFNESS = 260
const DAMPING = 12
/** 按下不弹，用指数收敛，时间常数 45ms，150ms 左右就贴到 0.97 了。 */
const PRESS_TAU = 0.045
const PRESS_SCALE = 0.97
/** 单帧最多积分 32ms：切到后台再回来时，避免一次积太大步长把弹簧算飞。 */
const MAX_STEP = 0.032
const REST_DISTANCE = 0.0008
const REST_VELOCITY = 0.02

interface Spring {
  el: HTMLElement
  value: number
  velocity: number
  target: number
  frame: number
}

const springs = new WeakMap<HTMLElement, Spring>()
let pressed: Spring | null = null
let installed = false

export function installPressSpring() {
  if (installed || typeof window === 'undefined') return
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
  installed = true

  document.addEventListener('pointerdown', onPointerDown, true)
  document.addEventListener('pointerup', onPointerUp, true)
  document.addEventListener('pointercancel', onPointerUp, true)
  window.addEventListener('blur', releasePressed)
}

function onPointerDown(event: PointerEvent) {
  if (event.button !== 0) return
  const el = findButton(event.target)
  if (!el) return

  releasePressed()
  const spring = springOf(el)
  spring.target = PRESS_SCALE
  el.classList.add('press-spring', 'press-down')
  run(spring)
  pressed = spring
}

function onPointerUp() {
  releasePressed()
}

function releasePressed() {
  if (!pressed) return
  pressed.el.classList.remove('press-down')
  pressed.target = 1
  run(pressed)
  pressed = null
}

function findButton(target: EventTarget | null): HTMLElement | null {
  if (!(target instanceof Element)) return null
  const el = target.closest<HTMLElement>('button, .el-button, [data-press]')
  if (!el) return null
  // 禁用态不弹，否则点不动的东西也有反馈，更让人困惑
  if (el.hasAttribute('disabled') || el.classList.contains('is-disabled')) return null
  if (el.getAttribute('aria-disabled') === 'true') return null
  return el
}

function springOf(el: HTMLElement): Spring {
  const existing = springs.get(el)
  if (existing) return existing
  const created: Spring = { el, value: 1, velocity: 0, target: 1, frame: 0 }
  springs.set(el, created)
  return created
}

function run(spring: Spring) {
  if (spring.frame) return
  let last = performance.now()

  const tick = (now: number) => {
    const dt = Math.min((now - last) / 1000, MAX_STEP)
    last = now

    if (spring.target < 1) {
      // 按下：一阶收敛，不冲过头
      spring.value += (spring.target - spring.value) * (1 - Math.exp(-dt / PRESS_TAU))
      spring.velocity = 0
    } else {
      // 松手：弹簧回弹，刚度 260、阻尼 12，欠阻尼所以会略微过冲
      const acceleration = -STIFFNESS * (spring.value - spring.target) - DAMPING * spring.velocity
      spring.velocity += acceleration * dt
      spring.value += spring.velocity * dt
    }

    if (
      Math.abs(spring.value - spring.target) < REST_DISTANCE
      && Math.abs(spring.velocity) < REST_VELOCITY
    ) {
      spring.value = spring.target
      spring.velocity = 0
      spring.frame = 0
      spring.el.style.transform = spring.target === 1 ? '' : `scale(${spring.target})`
      spring.el.style.willChange = ''
      return
    }

    spring.el.style.transform = `scale(${spring.value.toFixed(4)})`
    spring.frame = requestAnimationFrame(tick)
  }

  spring.el.style.willChange = 'transform'
  spring.frame = requestAnimationFrame(tick)
}
