/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        industrial: {
          950: '#090d16',
          900: '#0f172a',
          850: '#151f33',
          800: '#1e293b',
          750: '#283548',
          700: '#334155',
          600: '#475569',
          500: '#64748b',
          400: '#94a3b8',
          300: '#cbd5e1',
          200: '#e2e8f0',
          100: '#f1f5f9',
          50: '#f8fafc',
        },
        surface: {
          canvas: '#f8fafc',
          panel: '#ffffff',
          subtle: '#f1f5f9',
          hover: '#e2e8f0',
        },
        period: {
          comparable: {
            DEFAULT: '#475569',
            subtle: '#f1f5f9',
            border: '#94a3b8',
            dark: '#334155',
          },
          actual: {
            DEFAULT: '#1d4ed8',
            subtle: '#eff6ff',
            border: '#60a5fa',
            dark: '#1e40af',
          },
        },
        variance: {
          unfavourable: {
            DEFAULT: '#9f1239',
            subtle: '#fff1f2',
            border: '#fecdd3',
          },
          favourable: {
            DEFAULT: '#065f46',
            subtle: '#ecfdf5',
            border: '#a7f3d0',
          },
          neutral: '#64748b',
        },
        anomaly: {
          boundary: {
            DEFAULT: '#c2410c',
            subtle: '#fff7ed',
            border: '#fdba74',
          },
          missing: '#94a3b8',
        },
        cost: {
          material: '#0f766e',
          labor: '#d97706',
          overhead: '#7c3aed',
        },
        provenance: {
          hard: '#0f172a',
          ai: {
            DEFAULT: '#6366f1',
            subtle: '#eef2ff',
            border: '#c7d2fe',
          },
        }
      },
      fontFamily: {
        mono: ['JetBrains Mono', 'Fira Code', 'Roboto Mono', 'ui-monospace', 'monospace'],
        sans: ['Inter', '-apple-system', 'BlinkMacSystemFont', 'Segoe UI', 'Roboto', 'sans-serif'],
      }
    },
  },
  plugins: [],
}

