import React, { createContext, useCallback, useContext, useEffect, useState } from 'react'

interface AuthCtx {
  token: string | null
  userId: string | null
  login: (username: string, password: string) => Promise<void>
  logout: () => void
  ensureAuthModal: () => void
  LoginModal: React.FC
}

const Ctx = createContext<AuthCtx | null>(null)

const STORAGE_KEY = 'todo_auth'

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [token, setToken] = useState<string | null>(null)
  const [userId, setUserId] = useState<string | null>(null)
  const [show, setShow] = useState(false)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    try {
      const raw = localStorage.getItem(STORAGE_KEY)
      if (raw) {
        const parsed = JSON.parse(raw)
        setToken(parsed.token)
        setUserId(parsed.userId)
      }
    } catch {}
  }, [])

  const persist = (t: string | null, u: string | null) => {
    if (t) localStorage.setItem(STORAGE_KEY, JSON.stringify({ token: t, userId: u }))
    else localStorage.removeItem(STORAGE_KEY)
  }

  const login = useCallback(async (username: string, password: string) => {
    setPending(true); setError(null)
    try {
      const res = await fetch('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password })
      })
      if (!res.ok) throw new Error(await res.text())
      const data = await res.json()
      const tok = data.token
      setToken(tok)
      // 获取 userId
      try {
        const meResp = await fetch('/api/auth/me', { headers: { 'Authorization': `Bearer ${tok}` } })
        if (meResp.ok) {
          const me = await meResp.json()
          setUserId(me.userId || username)
        } else setUserId(username)
      } catch { setUserId(username) }
      persist(tok, username)
      setShow(false)
    } catch (e: any) {
      setError(e.message || String(e))
    } finally { setPending(false) }
  }, [])

  const logout = useCallback(() => {
    setToken(null); setUserId(null); persist(null, null)
  }, [])

  const ensureAuthModal = useCallback(() => setShow(true), [])

  const LoginModal: React.FC = () => {
    const [u, setU] = useState('demo')
    const [p, setP] = useState('demo')
    if (!show) return null
    return (
      <div className="modal-backdrop" onClick={()=>setShow(false)}>
        <div className="modal" onClick={e=>e.stopPropagation()}>
          <h2>登录</h2>
          <div className="form">
            <input className="input" placeholder="用户名" value={u} onChange={e=>setU(e.target.value)} />
            <input className="input" type="password" placeholder="密码" value={p} onChange={e=>setP(e.target.value)} />
            {error && <div className="error">{error}</div>}
            <div className="actions">
              <button className="btn primary" disabled={pending} onClick={()=>login(u, p)}>{pending? '登录中...' : '登录'}</button>
              <button className="btn" onClick={()=>setShow(false)}>取消</button>
            </div>
          </div>
        </div>
      </div>
    )
  }

  return <Ctx.Provider value={{ token, userId, login, logout, ensureAuthModal, LoginModal }}>{children}</Ctx.Provider>
}

export const useAuth = () => {
  const ctx = useContext(Ctx)
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider')
  return ctx
}
