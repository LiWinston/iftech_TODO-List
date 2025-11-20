import React, { useCallback, useEffect, useRef, useState } from 'react'
import { useAuth } from './auth'
import './styles.css'

export type Todo = {
  id: string
  userId: string
  title: string
  description?: string
  statusCode: number
  createdAt: string
  priorityScore?: number
  priorityLabel?: string
  categoryId?: string
}

interface PageCursor { createdAt?: string; id?: string }

export default function App() {
  const { token, userId, ensureAuthModal, logout, LoginModal } = useAuth()
  const [todos, setTodos] = useState<Todo[]>([])
  const [hasMore, setHasMore] = useState(true)
  const [loading, setLoading] = useState(false)
  const cursor = useRef<PageCursor>({})
  const [title, setTitle] = useState('')
  const [desc, setDesc] = useState('')
  const [searchQ, setSearchQ] = useState('')
  const [searching, setSearching] = useState(false)
  const [searchResults, setSearchResults] = useState<Todo[]>([])
  const [view, setView] = useState<'list' | 'search'>('list')
  const [prioScore, setPrioScore] = useState<string>('0')
  const [prioLabel, setPrioLabel] = useState<string>('')
  const [category, setCategory] = useState<string>('')

  const size = useRef(20)
  const lastScrollTs = useRef<number>(Date.now())

  const authHeaders = () => ({ 'Authorization': token ? `Bearer ${token}` : '', 'X-User-ID': userId || '' })

  const fetchJson = useCallback(async (url: string, init?: RequestInit, autoLoginOn403: boolean = false) => {
    // 规范化 headers 为普通对象
    const base: Record<string,string> = {}
    if (init?.headers instanceof Headers) {
      init.headers.forEach((v,k)=>{ base[k]=v })
    } else if (Array.isArray(init?.headers)) {
      for (const [k,v] of init.headers as any) base[k]=v
    } else if (init?.headers) {
      Object.assign(base, init.headers as any)
    }
    const headers: Record<string,string> = { 'Content-Type': 'application/json', ...base, ...authHeaders() }
    const res = await fetch(url, { ...(init || {}), headers })
    if (res.status === 403 && autoLoginOn403) {
      ensureAuthModal()
      throw new Error('需要登录')
    }
    if (!res.ok) throw new Error(await res.text())
    const ct = res.headers.get('content-type')
    if (ct && ct.includes('application/json')) return res.json()
    return res.text()
  }, [token, userId, ensureAuthModal])

  const adjustSize = useCallback((deltaY: number) => {
    const now = Date.now()
    const dt = now - lastScrollTs.current
    lastScrollTs.current = now
    const speed = Math.abs(deltaY) / Math.max(1, dt)
    const next = Math.max(20, Math.min(150, Math.round(size.current * (1 + speed * 0.25))))
    size.current = next
  }, [])

  const loadMore = useCallback(async () => {
    if (loading || !hasMore || view !== 'list') return
    setLoading(true)
    try {
      const params = new URLSearchParams()
      params.set('size', String(size.current))
      if (cursor.current.createdAt) params.set('cursorCreatedAt', cursor.current.createdAt)
      if (cursor.current.id) params.set('cursorId', cursor.current.id)
      const data: Todo[] = await fetchJson(`/api/todos?${params.toString()}`)
      // 去重合并：避免在开发模式（React StrictMode）或偶发重复请求时列表重复展示
      setTodos(prev => {
        if (prev.length === 0) return data
        const exists = new Set(prev.map(t => t.id))
        const toAdd = data.filter(t => !exists.has(t.id))
        return [...prev, ...toAdd]
      })
      if (data.length > 0) {
        const last = data[data.length - 1]
        cursor.current = { createdAt: last.createdAt, id: last.id }
        setHasMore(true)
      } else {
        setHasMore(false)
      }
    } catch (e) { console.warn(e) }
    finally { setLoading(false) }
  }, [loading, hasMore, view, fetchJson])

  useEffect(() => { loadMore() }, [view])

  const onScroll = useCallback((e: React.UIEvent<HTMLDivElement>) => {
    if (view !== 'list') return
    adjustSize((e.nativeEvent as any).deltaY ?? 1)
    const el = e.currentTarget
    if (el.scrollTop + el.clientHeight >= el.scrollHeight - 120) loadMore()
  }, [loadMore, adjustSize, view])

  const onCreate = useCallback(async () => {
    if (!title.trim()) return
    const body = {
      title,
      description: desc || null,
      priorityScore: prioScore ? Number(prioScore) : 0,
      priorityLabel: prioLabel || null,
      categoryId: category || null
    }
    try {
      const created = await fetchJson('/api/todos', { method: 'POST', body: JSON.stringify(body) }, true) as Todo
      setTodos([created, ...todos])
      setTitle('')
      setDesc('')
      setPrioScore('0')
      setPrioLabel('')
      setCategory('')
    } catch (e) { console.warn(e) }
  }, [title, desc, todos, fetchJson])

  const onDelete = useCallback(async (id: string) => {
    try {
      await fetchJson(`/api/todos/${id}/trash`, { method: 'POST' }, true)
      setTodos(prev => prev.filter(t => t.id !== id))
      if (view === 'search') setSearchResults(prev => prev.filter(t => t.id !== id))
    } catch (e) { console.warn(e) }
  }, [fetchJson, view])

  const onToggle = useCallback(async (id: string, completed: boolean) => {
    try {
      await fetchJson(`/api/todos/${id}/${completed ? 'uncomplete' : 'complete'}`, { method: 'POST' }, true)
      setTodos(prev => prev.map(t => t.id === id ? { ...t, statusCode: completed ? 0 : 1 } : t))
      if (view === 'search') setSearchResults(prev => prev.map(t => t.id === id ? { ...t, statusCode: completed ? 0 : 1 } : t))
    } catch (e) { console.warn(e) }
  }, [fetchJson, view])

  const doSearch = useCallback(async () => {
    if (!searchQ.trim()) { setView('list'); setSearchResults([]); return }
    setSearching(true)
    setView('search')
    try {
      const data = await fetchJson(`/api/todos/search?q=${encodeURIComponent(searchQ.trim())}`) as Todo[]
      setSearchResults(data)
    } catch (e) { console.warn(e) }
    finally { setSearching(false) }
  }, [searchQ, fetchJson])

  const clearSearch = useCallback(() => {
    setSearchQ('')
    setView('list')
    setSearchResults([])
  }, [])

  const list = view === 'list' ? todos : searchResults

  return (
    <div className="app-shell">
      <LoginModal />
      <header className="app-header">
        <div className="brand">📝 TODO List</div>
        <div className="search-bar">
          <input value={searchQ} onChange={e=>setSearchQ(e.target.value)} placeholder="搜索 (关键词 / 语义)" />
          <button className="btn" onClick={doSearch} disabled={searching}>{searching ? '搜索中...' : '搜索'}</button>
          {view==='search' && <button className="btn subtle" onClick={clearSearch}>清除</button>}
        </div>
        <div className="auth-area">
          {token ? (
            <>
              <span className="user-chip" title={userId||''}>{userId || '已登录'}</span>
              <button className="btn outline" onClick={logout}>退出</button>
            </>
          ) : (
            <button className="btn primary" onClick={ensureAuthModal}>登录</button>
          )}
        </div>
      </header>
      <main className="content">
        <section className="create-panel">
          <input className="input" placeholder={token? '新任务标题' : '登录后可添加任务'} value={title} onChange={e=>setTitle(e.target.value)} disabled={!token} />
          <input className="input" placeholder="描述" value={desc} onChange={e=>setDesc(e.target.value)} disabled={!token} />
          <input className="input" placeholder="优先级分数(0~..)" value={prioScore} onChange={e=>setPrioScore(e.target.value)} disabled={!token} />
          <input className="input" placeholder="优先级标签(如 High/Low)" value={prioLabel} onChange={e=>setPrioLabel(e.target.value)} disabled={!token} />
          <input className="input" placeholder="分类ID" value={category} onChange={e=>setCategory(e.target.value)} disabled={!token} />
          <button className="btn primary" onClick={onCreate} disabled={!token}>添加</button>
        </section>
        <section className="list-panel" onScroll={onScroll}>
          {list.map(t => (
            <div key={t.id} className={`card todo ${t.statusCode === 1 ? 'completed' : ''}`}>
              <div className="card-head">
                <h3 className="title">{t.title}</h3>
                <time className="timestamp">{new Date(t.createdAt).toLocaleString()}</time>
              </div>
              {t.description && <p className="desc">{t.description}</p>}
              <div className="desc">分类: {t.categoryId || '—'} · 优先级: {t.priorityLabel || t.priorityScore || '—'}</div>
              <div className="card-actions">
                <button className="btn" onClick={()=>onToggle(t.id, t.statusCode === 1)}>{t.statusCode === 1 ? '标记未完成' : '标记完成'}</button>
                <button className="btn danger" onClick={()=>onDelete(t.id)}>回收站</button>
              </div>
            </div>
          ))}
          {loading && view==='list' && <div className="loading">加载中...</div>}
          {view==='list' && !hasMore && <div className="end-tip">没有更多了</div>}
          {view==='search' && !searching && list.length===0 && <div className="empty">无搜索结果</div>}
        </section>
      </main>
      <footer className="app-footer">{token? '已登录，可写操作' : '未登录，只读模式'} · Keyset + 动态分页 · Hybrid 搜索</footer>
    </div>
  )
}
