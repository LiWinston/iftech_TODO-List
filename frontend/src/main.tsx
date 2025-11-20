import React from 'react'
import { createRoot } from 'react-dom/client'
import App from './ui/App'
import { AuthProvider } from './ui/auth'
import './ui/styles.css'

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <AuthProvider>
      <App />
    </AuthProvider>
  </React.StrictMode>
)
