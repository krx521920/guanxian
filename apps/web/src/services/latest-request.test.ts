import { describe, expect, it } from 'vitest'
import { createLatestRequestGate } from './latest-request'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise
  })
  return { promise, resolve }
}

describe('createLatestRequestGate', () => {
  it('只允许最后发起的异步请求提交结果', async () => {
    const gate = createLatestRequestGate()
    const first = deferred<string>()
    const second = deferred<string>()
    let committed = ''

    async function run(request: Promise<string>) {
      const epoch = gate.begin()
      const result = await request
      if (gate.isCurrent(epoch)) committed = result
    }

    const firstRun = run(first.promise)
    const secondRun = run(second.promise)

    second.resolve('协会 B')
    await secondRun
    first.resolve('协会 A')
    await firstRun

    expect(committed).toBe('协会 B')
  })

  it('失效处理会阻止已卸载页面的请求提交', () => {
    const gate = createLatestRequestGate()
    const epoch = gate.begin()

    gate.invalidate()

    expect(gate.isCurrent(epoch)).toBe(false)
  })
})
