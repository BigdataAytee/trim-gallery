import { motion } from 'motion/react'
import { ChevronLeft, Moon, Sun } from 'lucide-react'
import type { Theme } from '../hooks/useTheme'

interface Props {
  theme: Theme
  onToggleTheme: () => void
}

/**
 * The cream band from reference-mockup.png: circular back button, the title, and the
 * theme toggle, sticky at the top.
 */
export function Header({ theme, onToggleTheme }: Props) {
  const dark = theme === 'dark'

  return (
    <header
      className="sticky top-0 z-5 flex items-center gap-4 px-[18px] pb-[22px]"
      style={{
        paddingTop: 'calc(env(safe-area-inset-top) + 22px)',
        background: 'var(--band)',
        borderBottom: '1px solid color-mix(in srgb, var(--line) 60%, transparent)',
        transition: 'background 350ms',
      }}
    >
      <motion.button
        type="button"
        aria-label="Back"
        whileTap={{ scale: 0.94 }}
        className="grid size-[46px] shrink-0 place-items-center rounded-full"
        style={{ background: 'var(--card)', boxShadow: '0 1px 2px rgb(0 0 0 / 0.06)' }}
      >
        <ChevronLeft size={20} strokeWidth={2.6} />
      </motion.button>

      <h1
        className="flex-1 font-bold"
        style={{ fontSize: 23, letterSpacing: '-0.02em', lineHeight: 1.15 }}
      >
        Photos and clips from buyers
      </h1>

      <motion.button
        type="button"
        onClick={onToggleTheme}
        aria-label={dark ? 'Switch to light mode' : 'Switch to dark mode'}
        aria-pressed={dark}
        whileTap={{ scale: 0.94 }}
        className="grid size-[46px] shrink-0 place-items-center rounded-full"
        style={{ background: 'var(--card)', boxShadow: '0 1px 2px rgb(0 0 0 / 0.06)' }}
      >
        {/* The incoming icon spins into place, which reads as one control changing state
            rather than two different buttons. The prototype leaves the moon parked at
            200deg; here the spin is the transition and the icon settles upright, because
            a permanently rotated crescent reads as the wrong moon. */}
        <motion.span
          key={theme}
          className="grid place-items-center"
          initial={{ rotate: dark ? -200 : 200, opacity: 0 }}
          animate={{ rotate: 0, opacity: 1 }}
          transition={{ duration: 0.5, ease: [0.34, 1.4, 0.64, 1] }}
        >
          {dark ? <Moon size={20} strokeWidth={2} /> : <Sun size={20} strokeWidth={2} />}
        </motion.span>
      </motion.button>
    </header>
  )
}
