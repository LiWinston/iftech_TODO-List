import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'

export type Todo = {
  id: string
  userId: string
  title: string
  description?: string
  statusCode: number
  createdAt: string
}

const fetchJson = async (url: string, init?: RequestInit) => {
  const res = await fetch(url, {
    ...(init || {}),
    headers: {
      'Content-Type': 'application/json',
      'X-User-ID': 'demo-user',
      ...(init?.headers || {})
    }
  })
  if (!res.ok) throw new Error(await res.text())
  return res.json()
}

export default function App() {
  const [todos, setTodos] = useState<Todo[]>([])
  const [hasMore, setHasMore] = useState(true)
  const [loading, setLoading] = useState(false)
  const cursor = useRef<{ createdAt?: string; id?: string }>({})
  const [title, setTitle] = useState('')
  const [desc, setDesc] = useState('')

  const size = useRef(20)
  const lastScrollTs = useRef<number>(Date.now())
  const eventCount = useRef(0)

  const adjustSize = useCallback((deltaY: number) => {
    // 简单示意：滚动越快，加载N越大（20~150）
    const now = Date.now()
    const dt = now - lastScrollTs.current
    lastScrollTs.current = now
    eventCount.current += 1
    const speed = Math.abs(deltaY) / Math.max(1, dt)
    const next = Math.max(20, Math.min(150, Math.round(size.current * (1 + speed * 0.2))))
    size.current = next
  }, [])

  const loadMore = useCallback(async () => {
    if (loading || !hasMore) return
    setLoading(true)
    try {
      const params = new URLSearchParams()
      params.set('size', String(size.current))
      if (cursor.current.createdAt) params.set('cursorCreatedAt', cursor.current.createdAt)
      if (cursor.current.id) params.set('cursorId', cursor.current.id)
      const data: Todo[] = await fetchJson(`/api/todos?${params.toString()}`)
      setTodos(prev => [...prev, ...data])
      if (data.length > 0) {
        const last = data[data.length - 1]
        cursor.current = { createdAt: last.createdAt, id: last.id }
        setHasMore(true)
      } else {
        setHasMore(false)
      }
    } finally {
      setLoading(false)
    }
  }, [loading, hasMore])

  useEffect(() => {
    loadMore()
  }, [])

  const onScroll = useCallback((e: React.UIEvent<HTMLDivElement>) => {
    adjustSize((e.nativeEvent as any).deltaY ?? 1)
    const el = e.currentTarget
    if (el.scrollTop + el.clientHeight >= el.scrollHeight - 100) {
      loadMore()
    }
  }, [loadMore, adjustSize])

  const onCreate = useCallback(async () => {
    if (!title.trim()) return
    const body = { title, description: desc }
    const created = await fetchJson('/api/todos', { method: 'POST', body: JSON.stringify(body) })
    setTodos([created, ...todos])
    setTitle('')
    setDesc('')
  }, [title, desc, todos])

  const onDelete = useCallback(async (id: string) => {
    await fetchJson(`/api/todos/${id}`, { method: 'DELETE' })
    setTodos(prev => prev.filter(t => t.id !== id))
  }, [])

  const onToggle = useCallback(async (id: string, completed: boolean) => {
    await fetchJson(`/api/todos/${id}/${completed ? 'uncomplete' : 'complete'}`, { method: 'POST' })
    setTodos(prev => prev.map(t => t.id === id ? { ...t, statusCode: completed ? 0 : 1 } : t))
  }, [])

  return (
    <div style={{ display:'flex', flexDirection:'column', height:'100vh', fontFamily:'Inter, system-ui, Arial' }}>
      <header style={{ padding:16, borderBottom:'1px solid #eee' }}>
        <h2>TODO List</h2>
        <div style={{ display:'flex', gap:8 }}>
          <input placeholder="标题" value={title} onChange={e=>setTitle(e.target.value)} />
          <input placeholder="描述" value={desc} onChange={e=>setDesc(e.target.value)} />
          <button onClick={onCreate}>添加</button>
        </div>
      </header>
      <div onScroll={onScroll} style={{ overflow:'auto', flex:1, padding:16 }}>
        {todos.map(t => (
          <div key={t.id} style={{ padding:12, marginBottom:8, border:'1px solid #eee', borderRadius:8, display:'flex', justifyContent:'space-between', alignItems:'center' }}>
            <div>
              <div style={{ fontWeight:600 }}>{t.title}</div>
              <div style={{ color:'#666' }}>{t.description}</div>
            </div>
            <div style={{ display:'flex', gap:8 }}>
              <button onClick={()=>onToggle(t.id, t.statusCode === 1)}>{t.statusCode === 1 ? '标记未完成' : '标记完成'}</button>
              <button onClick={()=>onDelete(t.id)}>删除</button>
            </div>
          </div>
        ))}
        {loading && <div>加载中...</div>}
        {!hasMore && <div style={{ color:'#888', textAlign:'center', padding:16 }}>没有更多了</div>}
      </div>
    </div>
  )
}
