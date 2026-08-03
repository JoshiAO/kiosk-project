import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { collection, query, onSnapshot, doc, setDoc, deleteDoc, addDoc } from 'firebase/firestore';
import { db } from '../firebase';
import { Package, Plus, Edit2, Trash2, Code, X } from 'lucide-react';

export default function Apps() {
  const [apps, setApps] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [showModal, setShowModal] = useState(false);
  const [editingAppId, setEditingAppId] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  // Form State
  const [appName, setAppName] = useState('');
  const [packageName, setPackageName] = useState('');
  const [versionName, setVersionName] = useState('');
  const [versionCode, setVersionCode] = useState('');
  const [downloadUrl, setDownloadUrl] = useState('');

  useEffect(() => {
    const q = query(collection(db, 'approved_apps'));
    const unsubscribe = onSnapshot(q, (snapshot) => {
      const appList = snapshot.docs.map(doc => ({ id: doc.id, ...doc.data() }));
      setApps(appList);
      setLoading(false);
    });
    return unsubscribe;
  }, []);

  const handleToggleActive = async (appId: string, currentStatus: boolean) => {
    await setDoc(doc(db, 'approved_apps', appId), { isActive: !currentStatus }, { merge: true });
  };

  const handleDelete = async (appId: string) => {
    if (window.confirm("Are you sure you want to remove this app? This will uninstall it from all kiosks.")) {
      await deleteDoc(doc(db, 'approved_apps', appId));
    }
  };

  const handleEdit = (app: any) => {
    setEditingAppId(app.id);
    setAppName(app.appName);
    setPackageName(app.packageName);
    setVersionName(app.versionName);
    setVersionCode(app.versionCode.toString());
    setDownloadUrl(app.downloadUrl || '');
    setShowModal(true);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setIsSubmitting(true);
    try {
      if (editingAppId) {
        await setDoc(doc(db, 'approved_apps', editingAppId), {
          appName,
          packageName,
          versionName,
          versionCode: parseInt(versionCode, 10),
          downloadUrl: downloadUrl || null
        }, { merge: true });
      } else {
        await addDoc(collection(db, 'approved_apps'), {
          appName,
          packageName,
          versionName,
          versionCode: parseInt(versionCode, 10),
          downloadUrl: downloadUrl || null,
          isActive: true,
          createdAt: new Date()
        });
      }
      setShowModal(false);
      setEditingAppId(null);
      // Reset form
      setAppName('');
      setPackageName('');
      setVersionName('');
      setVersionCode('');
      setDownloadUrl('');
    } catch (error) {
      console.error("Error saving document: ", error);
      alert("Failed to save app.");
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleDownloadUrlChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    let url = e.target.value;
    const gDriveMatch = url.match(/drive\.google\.com\/file\/d\/([a-zA-Z0-9_-]+)/);
    if (gDriveMatch && gDriveMatch[1]) {
      url = `https://drive.google.com/uc?export=download&id=${gDriveMatch[1]}`;
    }
    setDownloadUrl(url);
  };

  return (
    <div className="animate-fade-in">
      <header style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '40px' }}>
        <div>
          <h1 style={{ fontSize: '32px', marginBottom: '8px' }}>App Management</h1>
          <p style={{ color: 'var(--text-secondary)' }}>Manage applications deployed to your kiosk devices.</p>
        </div>
        <button 
          className="btn-primary" 
          style={{ display: 'flex', alignItems: 'center', gap: '8px' }}
          onClick={() => {
            setEditingAppId(null);
            setAppName('');
            setPackageName('');
            setVersionName('');
            setVersionCode('');
            setDownloadUrl('');
            setShowModal(true);
          }}
        >
          <Plus size={18} />
          Add New App
        </button>
      </header>

      {/* Add App Modal */}
      {showModal && createPortal(
        <div style={{
          position: 'fixed', top: 0, left: 0, right: 0, bottom: 0,
          background: 'rgba(0,0,0,0.7)', backdropFilter: 'blur(4px)',
          display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 9999
        }}>
          <div className="glass-panel animate-fade-in" style={{ width: '100%', maxWidth: '500px', padding: '32px' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '24px' }}>
              <h2 style={{ fontSize: '20px', margin: 0 }}>{editingAppId ? 'Edit Application' : 'Add New Application'}</h2>
              <button onClick={() => { setShowModal(false); setEditingAppId(null); }} style={{ color: 'var(--text-secondary)' }}>
                <X size={20} />
              </button>
            </div>
            
            <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
              <div>
                <label style={{ display: 'block', marginBottom: '8px', fontSize: '12px', color: 'var(--text-secondary)', textTransform: 'uppercase' }}>App Name</label>
                <input type="text" className="input-field" required placeholder="e.g. Chrome" value={appName} onChange={e => setAppName(e.target.value)} />
              </div>
              <div>
                <label style={{ display: 'block', marginBottom: '8px', fontSize: '12px', color: 'var(--text-secondary)', textTransform: 'uppercase' }}>Package Name</label>
                <input type="text" className="input-field" required placeholder="e.g. com.android.chrome" value={packageName} onChange={e => setPackageName(e.target.value)} />
              </div>
              <div style={{ display: 'flex', gap: '16px' }}>
                <div style={{ flex: 1 }}>
                  <label style={{ display: 'block', marginBottom: '8px', fontSize: '12px', color: 'var(--text-secondary)', textTransform: 'uppercase' }}>Version Name</label>
                  <input type="text" className="input-field" required placeholder="e.g. 1.0.0" value={versionName} onChange={e => setVersionName(e.target.value)} />
                </div>
                <div style={{ flex: 1 }}>
                  <label style={{ display: 'block', marginBottom: '8px', fontSize: '12px', color: 'var(--text-secondary)', textTransform: 'uppercase' }}>Version Code</label>
                  <input type="number" className="input-field" required placeholder="e.g. 1" value={versionCode} onChange={e => setVersionCode(e.target.value)} />
                </div>
              </div>
              <div>
                <label style={{ display: 'block', marginBottom: '8px', fontSize: '12px', color: 'var(--text-secondary)', textTransform: 'uppercase' }}>Download URL (Optional - for silent install)</label>
                <input type="url" className="input-field" placeholder="https://example.com/app.apk" value={downloadUrl} onChange={handleDownloadUrlChange} />
                <span style={{ fontSize: '11px', color: 'var(--text-secondary)', marginTop: '4px', display: 'block' }}>
                  * Leave URL empty for pre-installed native apps (e.g. com.android.dialer)
                </span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '12px', marginTop: '16px' }}>
                <button type="button" className="btn-secondary" onClick={() => { setShowModal(false); setEditingAppId(null); }}>Cancel</button>
                <button type="submit" className="btn-primary" disabled={isSubmitting}>
                  {isSubmitting ? 'Saving...' : 'Save App'}
                </button>
              </div>
            </form>
          </div>
        </div>,
        document.body
      )}

      {loading ? (
        <div style={{ padding: '40px', textAlign: 'center', color: 'var(--text-secondary)' }}>Loading apps...</div>
      ) : (
        <div className="glass-panel" style={{ overflow: 'hidden' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr style={{ borderBottom: '1px solid var(--surface-border)', background: 'rgba(255,255,255,0.02)' }}>
                <th style={{ padding: '16px 24px', textAlign: 'left', color: 'var(--text-secondary)', fontWeight: 500, fontSize: '13px' }}>App Details</th>
                <th style={{ padding: '16px 24px', textAlign: 'left', color: 'var(--text-secondary)', fontWeight: 500, fontSize: '13px' }}>Version</th>
                <th style={{ padding: '16px 24px', textAlign: 'left', color: 'var(--text-secondary)', fontWeight: 500, fontSize: '13px' }}>Remote Config</th>
                <th style={{ padding: '16px 24px', textAlign: 'left', color: 'var(--text-secondary)', fontWeight: 500, fontSize: '13px' }}>Status</th>
                <th style={{ padding: '16px 24px', textAlign: 'right', color: 'var(--text-secondary)', fontWeight: 500, fontSize: '13px' }}>Actions</th>
              </tr>
            </thead>
            <tbody>
              {apps.map(app => (
                <tr key={app.id} style={{ borderBottom: '1px solid var(--surface-border)' }}>
                  <td style={{ padding: '20px 24px' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '16px' }}>
                      <div style={{ width: '40px', height: '40px', borderRadius: '10px', background: 'var(--surface-color)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                        <Package size={20} color="var(--accent-blue)" />
                      </div>
                      <div>
                        <div style={{ fontWeight: 600, fontSize: '15px' }}>{app.appName}</div>
                        <div style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>{app.packageName}</div>
                      </div>
                    </div>
                  </td>
                  <td style={{ padding: '20px 24px' }}>
                    <div style={{ fontSize: '14px' }}>v{app.versionName}</div>
                    <div style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>Build {app.versionCode}</div>
                  </td>
                  <td style={{ padding: '20px 24px' }}>
                    {app.remoteConfig ? (
                      <span style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', fontSize: '12px', background: 'rgba(0, 210, 255, 0.1)', color: 'var(--accent-blue)', padding: '4px 10px', borderRadius: '20px' }}>
                        <Code size={12} /> Configured
                      </span>
                    ) : (
                      <span style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>None</span>
                    )}
                  </td>
                  <td style={{ padding: '20px 24px' }}>
                    <button 
                      onClick={() => handleToggleActive(app.id, app.isActive)}
                      style={{ 
                        background: app.isActive ? 'rgba(0, 255, 135, 0.1)' : 'rgba(255, 255, 255, 0.05)',
                        color: app.isActive ? 'var(--accent-green)' : 'var(--text-secondary)',
                        border: `1px solid ${app.isActive ? 'rgba(0, 255, 135, 0.2)' : 'rgba(255,255,255,0.1)'}`,
                        padding: '6px 12px', borderRadius: '20px', fontSize: '12px', fontWeight: 500
                      }}
                    >
                      {app.isActive ? 'Active' : 'Disabled'}
                    </button>
                  </td>
                  <td style={{ padding: '20px 24px', textAlign: 'right' }}>
                    <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px' }}>
                      <button className="btn-secondary" style={{ padding: '8px', borderRadius: '8px' }} onClick={() => handleEdit(app)}>
                        <Edit2 size={16} />
                      </button>
                      <button className="btn-secondary" style={{ padding: '8px', borderRadius: '8px' }} onClick={() => handleDelete(app.id)}>
                        <Trash2 size={16} color="var(--accent-red)" />
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
              
              {apps.length === 0 && (
                <tr>
                  <td colSpan={5} style={{ padding: '48px', textAlign: 'center', color: 'var(--text-secondary)' }}>
                    No applications deployed. Click "Add New App" to get started.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
