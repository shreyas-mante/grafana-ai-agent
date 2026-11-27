import { Icon } from '../components/Icon';

/** A static, illustrative rendering of an investigation (sample data) for the landing page. */
export default function ProductPreview() {
  const code = [
    [16, '/** Runs authorization and the receipt write… */', 'changed'],
    [17, 'public String charge(long amountCents) {', ''],
    [18, '    pool.acquire();', 'hot'],
    [19, '    try {', ''],
    [20, '        String id = gateway.authorize(amountCents);', 'changed'],
    [21, '        saveReceipt(id, amountCents);', ''],
  ] as const;
  return (
    <div className="preview" aria-label="Example investigation (sample data)" role="img">
      <div className="preview-bar"><span /><span /><span /><div className="preview-url"><Icon name="lock" size={11} />your-workspace / investigations / inv_7f3a</div></div>
      <div className="preview-body">
        <div className="preview-title">
          <div>
            <div className="preview-eyebrow">payment-service · last 15 min</div>
            <div className="preview-h">Connection pool exhausted by slow gateway calls</div>
          </div>
          <span className="badge badge-ok"><Icon name="checkCircle" />High confidence</span>
        </div>
        <div className="preview-stats">
          <div><span>5xx rate</span><b className="bad">69.8%</b><i>was 0.4%</i></div>
          <div><span>p95 latency</span><b className="bad">1.76 s</b><i>was 42 ms</i></div>
          <div><span>DB pool</span><b className="bad">100%</b><i>was 35%</i></div>
        </div>
        <div className="preview-code">
          <div className="preview-code-head"><Icon name="fileCode" size={13} />PaymentService.java:18<span>@ 5aef153</span></div>
          {code.map(([n, text, cls]) => (
            <div key={n} className={`pl ${cls}`}><span className="pn">{n}</span>{text}</div>
          ))}
        </div>
        <div className="preview-cause">
          <Icon name="sparkles" size={15} />
          <div><b>Likely cause.</b> Commit <code>5aef153</code> moved the gateway call inside the pooled section, so each request holds a DB connection for ~1.5 s.</div>
        </div>
        <div className="preview-steps">
          {['get_recent_errors', 'get_code_context', 'query_prometheus ×4', 'search_knowledge_base', 'get_recent_commits'].map((s) => (
            <span key={s}><Icon name="check" size={12} />{s}</span>
          ))}
        </div>
      </div>
    </div>
  );
}
