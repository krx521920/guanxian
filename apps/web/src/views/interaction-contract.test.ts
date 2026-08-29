import { readFileSync, readdirSync } from 'node:fs'
import { dirname, extname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

const sourceRoot = dirname(dirname(fileURLToPath(import.meta.url)))

function sourceFiles(directory: string, extension: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) return sourceFiles(path, extension)
    return extname(entry.name) === extension ? [path] : []
  })
}

function lineAt(source: string, offset: number): number {
  return source.slice(0, offset).split('\n').length
}

function buttonLabel(markup: string): string {
  const label = markup
    .replace(/<[^>]+>/g, ' ')
    .replace(/\{\{[\s\S]*?\}\}/g, '…')
    .replace(/\s+/g, ' ')
    .trim()
  return label || '(无文字按钮)'
}

function isSubmitButton(source: string, offset: number, openingTag: string): boolean {
  if (/type\s*=\s*["']submit["']/.test(openingTag)) return true
  if (/type\s*=/.test(openingTag)) return false

  const formStart = source.lastIndexOf('<form', offset)
  const formEnd = source.lastIndexOf('</form>', offset)
  if (formStart < 0 || formStart < formEnd) return false
  const formOpeningEnd = source.indexOf('>', formStart)
  if (formOpeningEnd < 0 || formOpeningEnd >= offset) return false
  const formOpeningTag = source.slice(formStart, formOpeningEnd + 1)
  return /(?:@submit|v-on:submit)(?:\.[\w-]+)*\s*=/.test(formOpeningTag)
}

describe('生产界面交互契约', () => {
  it('每个原生按钮都有点击行为或明确的表单提交语义', () => {
    const inertButtons: string[] = []
    const buttonPattern = /<button\b[^>]*\/>|<button\b[^>]*>[\s\S]*?<\/button>/gi

    for (const file of sourceFiles(sourceRoot, '.vue')) {
      const source = readFileSync(file, 'utf8')
      for (const match of source.matchAll(buttonPattern)) {
        const markup = match[0]
        const openingTag = markup.match(/^<button\b[^>]*>/i)?.[0] ?? markup
        const hasClick = /(?:@click|v-on:click)(?:\.[\w-]+)*\s*=/.test(openingTag)
        const submitsForm = isSubmitButton(source, match.index ?? 0, openingTag)
        if (!hasClick && !submitsForm) {
          inertButtons.push(
            `${relative(sourceRoot, file).replaceAll('\\', '/')}:${lineAt(source, match.index ?? 0)} ${buttonLabel(markup)}`,
          )
        }
      }
    }

    expect(inertButtons, `发现无实际行为的按钮：\n${inertButtons.join('\n')}`).toEqual([])
  })

  it('生产 API 客户端不引用模拟数据模块', () => {
    const apiClient = readFileSync(join(sourceRoot, 'services', 'platform-api.ts'), 'utf8')
    expect(apiClient).not.toMatch(/(?:from|import\s*\()\s*["'][^"']*mocks\/data["']/)
  })

  it('匹配详情把邀请、应答、洽谈、反馈和成果归档连接到显式用户操作', () => {
    const matchingView = readFileSync(join(sourceRoot, 'views', 'MatchingView.vue'), 'utf8')
    expect(matchingView).toContain('@submit.prevent="sendInvitation"')
    expect(matchingView).toContain('@submit.prevent="respondInvitation(invitation, true)"')
    expect(matchingView).toContain('@submit.prevent="addNegotiation"')
    expect(matchingView).toContain('@submit.prevent="submitFeedback"')
    expect(matchingView).toContain('@submit.prevent="archiveOutcome"')
    expect(matchingView).toContain('v-if="canRespondInvitation(invitation)"')
    expect(matchingView).toContain('v-if="canArchiveOutcome(selected)"')
  })
})
