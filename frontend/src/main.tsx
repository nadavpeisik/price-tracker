import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MotionConfig } from 'motion/react'
import { TooltipProvider } from '@/components/ui/tooltip'
import './index.css'
import App from './App.tsx'

const queryClient = new QueryClient()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      {/* reducedMotion="user" disables Motion animations under
          prefers-reduced-motion, live — pairs with useReducedMotion for the
          non-Motion effects (count-up, particles, stagger). */}
      <MotionConfig reducedMotion="user">
        <TooltipProvider delayDuration={200}>
          <App />
        </TooltipProvider>
      </MotionConfig>
    </QueryClientProvider>
  </StrictMode>,
)
