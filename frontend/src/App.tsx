import { lazy, Suspense, useCallback, useEffect, useState } from 'react';
import AppShell, { signOut } from './components/AppShell';
import { ErrorBoundary, FeedbackProvider, useFeedback } from './components/Feedback';
import { EmptyState, LinkButton, Spinner } from './components/ui';
import Landing from './marketing/Landing';
import Auth from './pages/Auth';
import { api } from './lib/api';
import { href, match, navigate, routes, useRoute } from './lib/router';
import type { ActionItem, Me, Meta } from './lib/types';

const Overview = lazy(() => import('./pages/app/Overview'));
const Investigations = lazy(() => import('./pages/app/Investigations'));
const InvestigationView = lazy(() => import('./pages/app/InvestigationView'));
const Errors = lazy(() => import('./pages/app/Errors'));
const Knowledge = lazy(() => import('./pages/app/Knowledge'));
const Schedules = lazy(() => import('./pages/app/Schedules'));
const Actions = lazy(() => import('./pages/app/Actions'));
const Integrate = lazy(() => import('./pages/app/Integrate'));
const Settings = lazy(() => import('./pages/app/Settings'));

const INVITE_KEY = 'pa_pending_invite';

export default function App() {
  return (
    <FeedbackProvider>
      <Root />
    </FeedbackProvider>
  );
}

function Root() {
  const path = useRoute();
  const { toast } = useFeedback();
  const [me, setMe] = useState<Me | null | undefined>(undefined);
  const [meta, setMeta] = useState<Meta | null>(null);
  const [pending, setPending] = useState(0);

  const loadMe = useCallback(() => api<Me>('/api/auth/me').then(setMe).catch(() => setMe(null)), []);
  const loadMeta = useCallback(() => {
    api<Meta>('/api/meta').then(setMeta).catch(() => {});
    api<ActionItem[]>('/api/actions').then((a) => setPending(a.filter((x) => x.status === 'PENDING').length)).catch(() => {});
  }, []);

  useEffect(() => {
    loadMe();
    const onUnauth = () => setMe(null);
    window.addEventListener('pa-unauthorized', onUnauth);
    return () => window.removeEventListener('pa-unauthorized', onUnauth);
  }, [loadMe]);

  useEffect(() => {
    if (me) loadMeta();
  }, [me, path, loadMeta]);

  // Legacy links (#/inv/…) from older notifications.
  const legacy = match(/^\/inv\/(inv_[0-9a-f]{16})$/, path);
  useEffect(() => {
    if (legacy) navigate(routes.investigation(legacy[0]), true);
  }, [legacy?.[0]]);

  // Invite links: signed-in users join immediately; others sign up (or sign in, then join).
  const invite = match(/^\/join\/([A-Za-z0-9_-]{20,100})$/, path)?.[0];
  useEffect(() => {
    if (invite) sessionStorage.setItem(INVITE_KEY, invite);
  }, [invite]);
  useEffect(() => {
    const pendingInvite = sessionStorage.getItem(INVITE_KEY);
    if (!me || !pendingInvite) return;
    sessionStorage.removeItem(INVITE_KEY);
    api('/api/auth/join', 'POST', { inviteToken: pendingInvite })
      .then(() => { toast('You joined the workspace'); loadMe(); })
      .catch((e) => toast(e.message, 'bad'))
      .finally(() => navigate(routes.app, true));
  }, [me, invite]);

  // Signed-in users on sign-in pages go to the app.
  const redirectToApp = !!me && (path === routes.login || path === routes.signup);
  useEffect(() => {
    if (redirectToApp) navigate(routes.app, true);
  }, [redirectToApp]);

  if (me === undefined) return <div className="center-screen"><Spinner /></div>;

  // Public routes
  if (path === routes.home) return <Landing signedIn={!!me} />;
  if (!me) {
    if (invite) return <Auth mode="join" invite={invite} onDone={loadMe} />;
    if (path === routes.signup) return <Auth mode="signup" onDone={loadMe} />;
    return <Auth mode="login" onDone={loadMe} />;
  }
  if (redirectToApp || invite) return <div className="center-screen"><Spinner /></div>;
  if (!path.startsWith('/app')) return <NotFound />;

  const isOwner = me.role === 'OWNER';
  const invId = match(/^\/app\/inv\/(inv_[0-9a-f]{16})$/, path)?.[0];
  let page;
  let active = path;
  if (invId) { page = <InvestigationView id={invId} />; active = routes.investigations; }
  else if (path === routes.app) page = <Overview me={me} meta={meta} />;
  else if (path === routes.investigations) page = <Investigations />;
  else if (path === routes.errors) page = <Errors />;
  else if (path === routes.knowledge) page = <Knowledge />;
  else if (path === routes.schedules) page = <Schedules />;
  else if (path === routes.actions) page = <Actions isOwner={isOwner} onChange={loadMeta} />;
  else if (path === routes.integrate) page = <Integrate />;
  else if (path === routes.settings) page = isOwner ? <Settings me={me} onChange={loadMeta} /> : <NotFound inApp />;
  else page = <NotFound inApp />;

  return (
    <AppShell me={me} meta={meta} active={active} pendingActions={pending}
              onSignOut={async () => { await signOut(); setMe(null); }}
              onSwitch={async (id) => {
                try {
                  await api('/api/auth/switch', 'POST', { workspaceId: id });
                  navigate(routes.app);
                  await loadMe();
                  toast('Switched workspace');
                } catch (e: any) { toast(e.message, 'bad'); }
              }}>
      <ErrorBoundary resetKey={path}>
        <Suspense fallback={<div className="page"><Spinner /></div>}>{page}</Suspense>
      </ErrorBoundary>
    </AppShell>
  );
}

function NotFound({ inApp }: { inApp?: boolean }) {
  const body = (
    <EmptyState icon="globe" title="Page not found" action={<LinkButton href={href(inApp ? routes.app : routes.home)} variant="primary" icon="arrowLeft">{inApp ? 'Back to overview' : 'Back to home'}</LinkButton>}>
      The page you’re looking for doesn’t exist or was moved.
    </EmptyState>
  );
  return inApp ? <div className="page">{body}</div> : <div className="center-screen">{body}</div>;
}
