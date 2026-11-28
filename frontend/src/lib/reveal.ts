import { useEffect } from 'react';

/**
 * Fades elements in as they scroll into view. Elements stay fully visible when JavaScript,
 * IntersectionObserver or motion is unavailable: the hidden state is only added here.
 */
export function useReveal(selector: string) {
  useEffect(() => {
    if (typeof IntersectionObserver === 'undefined') return;
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    const els = Array.from(document.querySelectorAll<HTMLElement>(selector));
    const io = new IntersectionObserver((entries) => {
      for (const e of entries) {
        if (e.isIntersecting) {
          const el = e.target as HTMLElement;
          el.classList.add('in');
          io.unobserve(el);
          // Hand the element back to its own hover transitions once it has appeared.
          const delay = 750 + Number(el.style.getPropertyValue('--i') || 0) * 70;
          window.setTimeout(() => el.classList.remove('reveal', 'in'), delay);
        }
      }
    }, { rootMargin: '0px 0px -8% 0px', threshold: 0.08 });
    for (const el of els) {
      const siblings = el.parentElement ? Array.from(el.parentElement.children).filter((c) => c.matches(selector)) : [];
      el.style.setProperty('--i', String(Math.min(Math.max(siblings.indexOf(el), 0), 6)));
      el.classList.add('reveal');
      io.observe(el);
    }
    return () => {
      io.disconnect();
      for (const el of els) el.classList.remove('reveal', 'in');
    };
  }, [selector]);
}
