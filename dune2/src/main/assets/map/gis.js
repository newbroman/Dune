// Dune GIS: layers from files (GeoPackage, shapefile, GeoJSON, KML/KMZ, GPX, CSV) and web map
// services (WMS) on Dune's map, plus survey tools – OS grid references, measuring, and points with
// notes – exported as GeoPackage or GeoJSON for QGIS. Loaded after index.html's own script, so
// `map` and `me` are shared.
(function () {
  const D = window.Dune || {};
  const call = (f, ...a) => { try { if (D[f]) D[f](...a); } catch (e) {} };

  // ---- Coordinates: British National Grid (EPSG:27700) and friends.
  proj4.defs('EPSG:27700', '+proj=tmerc +lat_0=49 +lon_0=-2 +k=0.9996012717 +x_0=400000 +y_0=-100000 +ellps=airy ' +
    '+towgs84=446.448,-125.157,542.06,0.15,0.247,0.842,-20.489 +units=m +no_defs');
  proj4.defs('EPSG:29903', '+proj=tmerc +lat_0=53.5 +lon_0=-8 +k=1.000035 +x_0=200000 +y_0=250000 +ellps=mod_airy ' +
    '+towgs84=482.5,-130.6,564.6,-1.042,-0.214,-0.631,8.15 +units=m +no_defs');
  proj4.defs('EPSG:2157', '+proj=tmerc +lat_0=53.5 +lon_0=-8 +k=0.99982 +x_0=600000 +y_0=750000 +ellps=GRS80 +towgs84=0,0,0,0,0,0,0 +units=m +no_defs');
  proj4.defs('EPSG:32630', '+proj=utm +zone=30 +datum=WGS84 +units=m +no_defs');
  proj4.defs('EPSG:4258', '+proj=longlat +ellps=GRS80 +no_defs');
  const toBNG = (lat, lon) => proj4('EPSG:4326', 'EPSG:27700', [lon, lat]);

  /** "SH 41234 63456" – a 10-figure OS grid reference (1 m), or null outside the grid. */
  function gridRef(lat, lon, digits = 10) {
    const [e, n] = toBNG(lat, lon);
    if (!(e >= 0 && e < 700000 && n >= 0 && n < 1300000)) return null;
    const ef = Math.floor(e / 100000), nf = Math.floor(n / 100000);
    let l1 = (19 - nf) - (19 - nf) % 5 + Math.floor((ef + 10) / 5);
    let l2 = (19 - nf) * 5 % 25 + ef % 5;
    if (l1 > 7) l1++; if (l2 > 7) l2++;
    const d = digits / 2, f = v => String(Math.floor((v % 100000) / Math.pow(10, 5 - d))).padStart(d, '0');
    return String.fromCharCode(65 + l1, 65 + l2) + ' ' + f(e) + ' ' + f(n);
  }
  window.gridRef = gridRef;
  const fmtEN = (lat, lon) => { const [e, n] = toBNG(lat, lon); return 'E ' + e.toFixed(1) + '  N ' + n.toFixed(1); };

  // Tell Dune the grid reference of where you are, as the GPS moves.
  const setPos0 = window.setPos;
  window.setPos = (lat, lon, acc, alt) => {
    window.meAcc = acc; window.meAlt = alt;
    setPos0(lat, lon, acc);
    const g = gridRef(lat, lon); call('grid', g || '');
  };

  // ---- Projections in files.
  const KNOWN = { 4326: 'EPSG:4326', 4258: 'EPSG:4258', 3857: 'EPSG:3857', 900913: 'EPSG:3857', 27700: 'EPSG:27700',
                  29903: 'EPSG:29903', 2157: 'EPSG:2157', 32630: 'EPSG:32630' };
  function projFrom(code, wkt) {
    if (code != null && KNOWN[code]) return KNOWN[code];
    if (wkt) {
      const m = /AUTHORITY\["EPSG","?(\d+)"?\]\]?\s*$/.exec(wkt.trim()) || /ID\["EPSG",(\d+)\]\]?\s*$/.exec(wkt.trim());
      if (m && KNOWN[m[1]]) return KNOWN[m[1]];
      if (/British_National_Grid|OSGB.?1936|OSGB36/i.test(wkt)) return 'EPSG:27700';
      try { proj4(wkt, 'EPSG:4326', [0, 0]); return wkt; } catch (e) {}
    }
    return null;
  }
  function reproject(gj, from) {
    if (!from || from === 'EPSG:4326') return gj;
    const t = proj4(from, 'EPSG:4326');
    const conv = c => typeof c[0] === 'number' ? (() => { const p = t.forward([c[0], c[1]]); return c.length > 2 ? [p[0], p[1], c[2]] : p; })() : c.map(conv);
    const geom = g => { if (!g) return g; if (g.type === 'GeometryCollection') return { ...g, geometries: g.geometries.map(geom) }; return { ...g, coordinates: conv(g.coordinates) }; };
    return { ...gj, features: gj.features.map(f => ({ ...f, geometry: geom(f.geometry) })) };
  }
  /** Coordinates bigger than degrees, with no projection given: in Britain that's the National Grid. */
  function looksProjected(gj) {
    for (const f of gj.features) {
      let c = f.geometry && f.geometry.coordinates; if (!c) continue;
      while (Array.isArray(c[0])) c = c[0];
      return Math.abs(c[0]) > 180 || Math.abs(c[1]) > 90;
    }
    return false;
  }
  function crsOf(gj) {
    const n = gj.crs && gj.crs.properties && gj.crs.properties.name; if (!n) return null;
    const m = /(\d+)\s*$/.exec(n); if (/CRS84/i.test(n)) return 'EPSG:4326';
    return m ? (KNOWN[m[1]] || null) : null;
  }
  const fc = feats => ({ type: 'FeatureCollection', features: feats });

  // ---- Readers. Each turns a file into GeoJSON in WGS84, or a list of { name, gj } for files with several layers.
  const libs = {};
  function lib(src) {
    return libs[src] || (libs[src] = new Promise((ok, no) => {
      const s = document.createElement('script'); s.src = src; s.onload = ok; s.onerror = () => no(new Error('could not load ' + src));
      document.head.appendChild(s);
    }));
  }
  const text = buf => new TextDecoder().decode(buf);

  async function readGeoJSON(buf) {
    let gj = JSON.parse(text(buf));
    if (gj.type === 'Feature') gj = fc([gj]); else if (gj.type !== 'FeatureCollection') gj = fc([{ type: 'Feature', geometry: gj, properties: {} }]);
    const from = crsOf(gj) || (looksProjected(gj) ? 'EPSG:27700' : null);
    return reproject(gj, from);
  }
  async function readXml(buf, kind) {
    await lib('lib/togeojson.js');
    const doc = new DOMParser().parseFromString(text(buf), 'text/xml');
    const gj = kind === 'gpx' ? toGeoJSON.gpx(doc) : toGeoJSON.kml(doc);
    gj.features = gj.features.filter(f => f.geometry);
    return gj;
  }
  async function readKmz(buf) {
    await lib('lib/fflate.js');
    const files = fflate.unzipSync(new Uint8Array(buf));
    const k = Object.keys(files).find(n => /\.kml$/i.test(n));
    if (!k) throw new Error('no .kml inside the .kmz');
    return readXml(files[k].buffer.slice(files[k].byteOffset, files[k].byteOffset + files[k].byteLength), 'kml');
  }
  async function readShape(buf) {
    await lib('lib/shp.js');
    let out = await shp(buf);   // reprojects using the .prj when there is one
    const list = (Array.isArray(out) ? out : [out]).map(g => ({ name: g.fileName || '', gj: looksProjected(g) ? reproject(g, 'EPSG:27700') : g }));
    return list.length === 1 ? list[0].gj : list;
  }
  function csvRows(t) {
    const rows = []; let row = [], cur = '', q = false;
    for (let i = 0; i < t.length; i++) {
      const ch = t[i];
      if (q) { if (ch === '"') { if (t[i + 1] === '"') { cur += '"'; i++; } else q = false; } else cur += ch; }
      else if (ch === '"') q = true;
      else if (ch === ',' || ch === ';' || ch === '\t') { row.push(cur); cur = ''; }
      else if (ch === '\n' || ch === '\r') { if (ch === '\r' && t[i + 1] === '\n') i++; row.push(cur); rows.push(row); row = []; cur = ''; }
      else cur += ch;
    }
    if (cur || row.length) { row.push(cur); rows.push(row); }
    return rows.filter(r => r.some(c => c.trim()));
  }
  async function readCsv(buf) {
    const rows = csvRows(text(buf)); if (rows.length < 2) throw new Error('empty CSV');
    const h = rows[0].map(c => c.trim().toLowerCase());
    const col = names => h.findIndex(c => names.includes(c));
    let x = col(['lon', 'lng', 'long', 'longitude', 'gpslon', 'x']), y = col(['lat', 'latitude', 'gpslat', 'y']);
    let from = null;
    const ex = col(['easting', 'east', 'e', 'bng_e', 'x_bng', 'os_e']), ny = col(['northing', 'north', 'n', 'bng_n', 'y_bng', 'os_n']);
    if ((x < 0 || y < 0) && ex >= 0 && ny >= 0) { x = ex; y = ny; from = 'EPSG:27700'; }
    if (x < 0 || y < 0) throw new Error('CSV needs lat/lon or easting/northing columns');
    const feats = [];
    rows.slice(1).forEach(r => {
      const a = parseFloat(r[x]), b = parseFloat(r[y]); if (!isFinite(a) || !isFinite(b)) return;
      const props = {}; rows[0].forEach((k, i) => { if (i !== x && i !== y) props[k.trim()] = r[i]; });
      feats.push({ type: 'Feature', geometry: { type: 'Point', coordinates: [a, b] }, properties: props });
    });
    let gj = fc(feats);
    if (!from && looksProjected(gj)) from = 'EPSG:27700';
    return reproject(gj, from);
  }

  // GeoPackage: an SQLite file. Geometry is a small header and then ordinary WKB.
  let SQL = null;
  async function sqlite() {
    if (SQL) return SQL;
    await lib('lib/sql-wasm.js');
    SQL = await initSqlJs({ locateFile: f => 'lib/' + f });
    return SQL;
  }
  function wkb(dv, o) {
    const le = dv.getUint8(o) === 1; o += 1;
    let t = dv.getUint32(o, le); o += 4;
    let dims = 2;
    if (t & 0x20000000) o += 4;                              // EWKB with SRID
    if (t & 0x80000000) dims++; if (t & 0x40000000) dims++;   // EWKB Z / M
    t &= 0x0fffffff;
    const iso = Math.floor(t / 1000); if (iso === 1 || iso === 2) dims = 3; else if (iso === 3) dims = 4;
    t %= 1000;
    const pt = () => { const c = [dv.getFloat64(o, le), dv.getFloat64(o + 8, le)]; if (dims > 2) c.push(dv.getFloat64(o + 16, le)); o += 8 * dims; return c; };
    const n = () => { const v = dv.getUint32(o, le); o += 4; return v; };
    const line = () => { const k = n(), a = []; for (let i = 0; i < k; i++) a.push(pt()); return a; };
    const poly = () => { const k = n(), a = []; for (let i = 0; i < k; i++) a.push(line()); return a; };
    const multi = () => { const k = n(), a = []; for (let i = 0; i < k; i++) { const r = wkb(dv, o); o = r.o; a.push(r.g); } return a; };
    let g;
    switch (t) {
      case 1: g = { type: 'Point', coordinates: pt() }; break;
      case 2: g = { type: 'LineString', coordinates: line() }; break;
      case 3: g = { type: 'Polygon', coordinates: poly() }; break;
      case 4: g = { type: 'MultiPoint', coordinates: multi().map(p => p.coordinates) }; break;
      case 5: g = { type: 'MultiLineString', coordinates: multi().map(p => p.coordinates) }; break;
      case 6: g = { type: 'MultiPolygon', coordinates: multi().map(p => p.coordinates) }; break;
      case 7: g = { type: 'GeometryCollection', geometries: multi() }; break;
      default: throw new Error('geometry type ' + t);
    }
    return { g, o };
  }
  function gpGeom(bytes) {
    if (!bytes || bytes.length < 8 || bytes[0] !== 0x47 || bytes[1] !== 0x50) return null;
    const flags = bytes[3]; if (flags & 0x10) return null;   // empty geometry
    const env = [0, 32, 48, 48, 64][(flags >> 1) & 7] || 0;
    const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    return wkb(dv, 8 + env).g;
  }
  const MAX_FEATURES = 50000;
  async function readGpkg(buf) {
    const S = await sqlite();
    const db = new S.Database(new Uint8Array(buf));
    try {
      const q = (sql, p) => { const s = db.prepare(sql); if (p) s.bind(p); const out = []; while (s.step()) out.push(s.getAsObject()); s.free(); return out; };
      const tables = q("SELECT c.table_name t, g.column_name col, g.srs_id srs FROM gpkg_contents c JOIN gpkg_geometry_columns g ON g.table_name = c.table_name WHERE c.data_type = 'features'");
      if (!tables.length) throw new Error('no feature tables (raster GeoPackages aren\'t supported yet)');
      const list = tables.map(({ t, col, srs }) => {
        const def = q('SELECT organization o, organization_coordsys_id c, definition d FROM gpkg_spatial_ref_sys WHERE srs_id = ?', [srs])[0] || {};
        const from = projFrom(String(def.o).toUpperCase() === 'EPSG' ? def.c : srs, def.d);
        const feats = [];
        const st = db.prepare('SELECT * FROM "' + t.replace(/"/g, '""') + '" LIMIT ' + MAX_FEATURES);
        while (st.step()) {
          const r = st.getAsObject(); let geometry = null;
          try { geometry = gpGeom(r[col]); } catch (e) {}
          delete r[col];
          if (geometry) feats.push({ type: 'Feature', geometry, properties: r });
        }
        st.free();
        let gj = fc(feats);
        return { name: t, gj: reproject(gj, from || (looksProjected(gj) ? 'EPSG:27700' : null)) };
      });
      return list.length === 1 ? list[0].gj : list;
    } finally { db.close(); }
  }

  async function readFile(url, fmt) {
    const r = await fetch(url); if (!r.ok) throw new Error('file missing');
    const buf = await r.arrayBuffer();
    switch (fmt) {
      case 'gpkg': return readGpkg(buf);
      case 'zip': case 'shp': return readShape(buf);
      case 'kml': return readXml(buf, 'kml');
      case 'kmz': return readKmz(buf);
      case 'gpx': return readXml(buf, 'gpx');
      case 'csv': return readCsv(buf);
      default: return readGeoJSON(buf);
    }
  }

  // ---- Drawing layers.
  const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);
  const LABEL_KEYS = ['well', 'name', 'Name', 'NAME', 'id', 'ID', 'label', 'title', 'site', 'SITE_NAME', 'sitename'];
  function popupFor(f, layer, ll) {
    const p = f.properties || {};
    const rows = Object.keys(p).filter(k => p[k] !== null && p[k] !== '' && typeof p[k] !== 'object' && !/^(styleUrl|styleHash|stroke|fill|icon)/.test(k))
      .slice(0, 40).map(k => '<tr><th>' + esc(k) + '</th><td>' + esc(p[k]) + '</td></tr>').join('');
    let html = '<div class="gp"><b>' + esc(layer.name) + '</b><table>' + rows + '</table>';
    if (ll) { const g = gridRef(ll.lat, ll.lng); html += '<div class="gpc">' + (g ? g + '<br>' : '') + ll.lat.toFixed(6) + ', ' + ll.lng.toFixed(6) + '</div>'; }
    if (layer.kind === 'wells' && p.well) html += '<button onclick="Dune.openLogger(\'' + esc(p.well) + '\')">Log a reading at ' + esc(p.well) + '</button>';
    return html + '</div>';
  }
  const shown = {};   // id → { layer, def, status }
  const canvas = L.canvas({ padding: 0.3 });
  function draw(def, gj) {
    const col = def.color || '#e3a35b';
    const points = gj.features.filter(f => f.geometry && /Point/.test(f.geometry.type)).length;
    const label = points > 0 && points <= 600;
    const lyr = L.geoJSON(gj, {
      renderer: canvas,
      style: () => ({ color: col, weight: 2, opacity: .95, fillColor: col, fillOpacity: .18 }),
      pointToLayer: (f, ll) => L.circleMarker(ll, { renderer: canvas, radius: def.kind === 'wells' ? 6 : 5, color: '#000', weight: 1.5, fillColor: col, fillOpacity: .95 }),
      onEachFeature: (f, l) => {
        l.bindPopup(() => popupFor(f, def, l.getLatLng ? l.getLatLng() : null), { maxWidth: 280, maxHeight: 320 });
        if (label && f.geometry.type === 'Point') {
          const p = f.properties || {}, k = LABEL_KEYS.find(k => p[k] != null && p[k] !== '');
          if (k) l.bindTooltip(esc(p[k]), { permanent: true, direction: 'right', offset: [6, 0], className: 'flab' });
        }
      },
    });
    return lyr;
  }
  async function load(def) {
    if (def.kind === 'wms') {
      const w = def.wms;
      return L.tileLayer.wms(w.url, { layers: w.layers, styles: w.styles || '', format: w.format || 'image/png', transparent: true,
        version: w.version || '1.1.1', opacity: w.opacity == null ? .7 : w.opacity, maxZoom: 21, attribution: w.attr || '' });
    }
    let r = await readFile(def.url, def.fmt);
    if (Array.isArray(r)) {   // several layers in one file: show them together, each table its own colour shade
      const g = L.featureGroup(); let n = 0;
      r.forEach((x, i) => { n += x.gj.features.length; draw({ ...def, name: def.name + ' · ' + x.name, color: shade(def.color, i) }, x.gj).addTo(g); });
      g._count = n; return g;
    }
    const lyr = draw(def, r); lyr._count = r.features.length; return lyr;
  }
  function shade(c, i) { const h = ['#e3a35b', '#7fb2ff', '#5fd38a', '#ff7a8a', '#c9a0ff', '#ffd84a', '#4fd1d9']; return i ? h[(h.indexOf(c) + i + 7) % 7] || h[i % 7] : c; }

  /** Dune sends the full layer list whenever it changes; draw what's on, drop what's gone. */
  window.gisSetLayers = async (list) => {
    const ids = new Set(list.map(d => d.id));
    Object.keys(shown).forEach(id => { if (!ids.has(id)) { if (shown[id].layer) map.removeLayer(shown[id].layer); delete shown[id]; } });
    for (const def of list) {
      let s = shown[def.id];
      if (s && JSON.stringify(s.def) !== JSON.stringify(def) && s.layer) {
        // A change of opacity or colour only: restyle without reloading the file.
        if (def.kind === 'wms' && s.layer.setOpacity) s.layer.setOpacity(def.wms.opacity == null ? .7 : def.wms.opacity);
        else if (s.def.color !== def.color) { map.removeLayer(s.layer); s = null; delete shown[def.id]; }
        if (s) s.def = def;
      }
      if (!s) { s = shown[def.id] = { def, layer: null, loading: null }; }
      if (def.on && !s.layer && !s.loading) {
        s.loading = load(def).then(l => {
          s.layer = l; s.loading = null;
          if (shown[def.id] === s && s.def.on) l.addTo(map);
          call('layerStatus', def.id, def.kind === 'wms' ? 'web map' : (l._count + ' feature' + (l._count === 1 ? '' : 's')));
          if (def.zoom && l.getBounds && l.getBounds().isValid()) zoomTo(l);
        }).catch(e => { s.loading = null; call('layerStatus', def.id, '⚠ ' + (e.message || e)); });
      } else if (s.layer) {
        if (def.on && !map.hasLayer(s.layer)) s.layer.addTo(map);
        if (!def.on && map.hasLayer(s.layer)) map.removeLayer(s.layer);
      }
    }
    // Keep web maps under the drawn layers, in list order.
    list.filter(d => d.kind === 'wms').forEach((d, i) => { const l = shown[d.id] && shown[d.id].layer; if (l && l.setZIndex) l.setZIndex(10 + i); });
  };
  function zoomTo(l) { follow = false; call('following', false); map.fitBounds(l.getBounds(), { padding: [24, 24], maxZoom: 18 }); }
  window.gisZoom = id => { const s = shown[id]; if (s && s.layer && s.layer.getBounds && s.layer.getBounds().isValid()) zoomTo(s.layer); };
  map.on('zoomend', () => map.getContainer().classList.toggle('nolabels', map.getZoom() < 14));
  map.getContainer().classList.toggle('nolabels', map.getZoom() < 14);

  // ---- Survey: points, lines and areas you make, kept by Dune and exported for QGIS.
  let survey = fc([]);
  const surveyLayer = L.geoJSON(null, {
    style: () => ({ color: '#ff9b3d', weight: 3, fillColor: '#ff9b3d', fillOpacity: .2 }),
    pointToLayer: (f, ll) => L.circleMarker(ll, { radius: 7, color: '#000', weight: 2, fillColor: '#ff9b3d', fillOpacity: 1 }),
    onEachFeature: (f, l) => {
      const p = f.properties;
      if (p.name) l.bindTooltip(esc(p.name), { permanent: true, direction: 'right', offset: [7, 0], className: 'flab slab' });
      l.bindPopup(() => {
        const rows = ['note', 'gridref', 'time', 'accuracy_m', 'gps_alt_m', 'length_m', 'area_m2', 'perimeter_m'].filter(k => p[k] != null && p[k] !== '')
          .map(k => '<tr><th>' + k.replace('_', ' ') + '</th><td>' + esc(p[k]) + '</td></tr>').join('');
        return '<div class="gp"><b>' + esc(p.name || 'Survey') + '</b><table>' + rows + '</table>' +
          '<button onclick="gisDeleteSurvey(\'' + p.uid + '\')">Delete</button></div>';
      });
    },
  }).addTo(map);
  function redrawSurvey() { surveyLayer.clearLayers(); surveyLayer.addData(survey); }
  function saveSurvey() { redrawSurvey(); call('surveySaved', JSON.stringify(survey)); call('surveyCount', survey.features.length); }
  window.gisSetSurvey = (gj) => { survey = gj && gj.features ? gj : fc([]); redrawSurvey(); call('surveyCount', survey.features.length); };
  window.gisDeleteSurvey = uid => { map.closePopup(); survey.features = survey.features.filter(f => f.properties.uid !== uid); saveSurvey(); };
  window.gisClearSurvey = () => { survey = fc([]); saveSurvey(); };
  const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
  const nextName = (pre) => pre + (survey.features.filter(f => (f.properties.name || '').startsWith(pre)).length + 1);

  // A small panel at the foot of the map for the survey tools.
  const panel = L.control({ position: 'bottomright' });
  let panelDiv = null;
  panel.onAdd = () => { panelDiv = L.DomUtil.create('div', 'spanel'); L.DomEvent.disableClickPropagation(panelDiv); L.DomEvent.disableScrollPropagation(panelDiv); return panelDiv; };
  function showPanel(html) { if (!panelDiv) panel.addTo(map); panelDiv.innerHTML = html; panelDiv.style.display = ''; }
  function hidePanel() { if (panelDiv) panelDiv.style.display = 'none'; }

  // Points: at your GPS position, at the map centre, or where you long-pressed.
  window.gisAddPoint = (where, lat, lon) => {
    let ll, acc = null, alt = null;
    if (where === 'gps') { if (!me) { call('toast', 'No GPS fix yet'); return; } ll = me.getLatLng(); acc = Math.round(window.meAcc || 0); alt = window.meAlt; }
    else if (where === 'at') ll = L.latLng(lat, lon);
    else ll = map.getCenter();
    map.closePopup();
    const g = gridRef(ll.lat, ll.lng), name = nextName('P');
    const pin = L.circleMarker(ll, { radius: 8, color: '#fff', weight: 2, fillColor: '#ff9b3d', fillOpacity: 1 }).addTo(map);
    showPanel('<b>New point</b><div class="gpc">' + (g || '') + '<br>' + ll.lat.toFixed(6) + ', ' + ll.lng.toFixed(6) +
      (acc ? '<br>±' + acc + ' m (GPS)' : where === 'centre' ? '<br>(map centre)' : '') + '</div>' +
      '<input id="sp_name" value="' + name + '" placeholder="Name"><textarea id="sp_note" rows="2" placeholder="Note"></textarea>' +
      '<div class="row"><button id="sp_ok">Save</button><button id="sp_no" class="dim">Cancel</button></div>');
    document.getElementById('sp_no').onclick = () => { map.removeLayer(pin); hidePanel(); };
    document.getElementById('sp_ok').onclick = () => {
      map.removeLayer(pin); hidePanel();
      const [e, n] = toBNG(ll.lat, ll.lng);
      survey.features.push({ type: 'Feature', geometry: { type: 'Point', coordinates: [+ll.lng.toFixed(7), +ll.lat.toFixed(7)] },
        properties: { uid: uid(), kind: 'point', name: document.getElementById('sp_name').value.trim() || name,
          note: document.getElementById('sp_note').value.trim(), time: new Date().toISOString(), gridref: g || '',
          easting: g ? +e.toFixed(2) : null, northing: g ? +n.toFixed(2) : null,
          accuracy_m: acc, gps_alt_m: alt == null || isNaN(alt) ? null : +(+alt).toFixed(1) } });
      saveSurvey();
    };
  };

  // Measuring: tap the map to add corners; a line gives its length, an area its size.
  let meas = null;
  function ringArea(pts) {   // square metres on the sphere
    const R = 6378137, rad = Math.PI / 180; let s = 0;
    for (let i = 0; i < pts.length; i++) { const a = pts[i], b = pts[(i + 1) % pts.length]; s += (b.lng - a.lng) * rad * (2 + Math.sin(a.lat * rad) + Math.sin(b.lat * rad)); }
    return Math.abs(s * R * R / 2);
  }
  const lengthOf = (pts, closed) => { let d = 0; for (let i = 1; i < pts.length; i++) d += pts[i - 1].distanceTo(pts[i]); if (closed && pts.length > 2) d += pts[pts.length - 1].distanceTo(pts[0]); return d; };
  const fmtLen = m => m >= 1000 ? (m / 1000).toFixed(2) + ' km' : m.toFixed(m < 10 ? 1 : 0) + ' m';
  const fmtArea = a => a >= 10000 ? (a / 10000).toFixed(2) + ' ha (' + Math.round(a).toLocaleString('en-GB') + ' m²)' : Math.round(a) + ' m²';
  function measPanel() {
    const pts = meas.pts, area = meas.kind === 'area';
    const v = area ? (pts.length > 2 ? fmtArea(ringArea(pts)) + '<br>perimeter ' + fmtLen(lengthOf(pts, true)) : 'tap 3 or more corners')
                   : (pts.length > 1 ? fmtLen(lengthOf(pts)) : 'tap the map to measure');
    showPanel('<b>' + (area ? 'Area' : 'Distance') + '</b><div class="mv">' + v + '</div>' +
      '<div class="row"><button id="m_undo" class="dim">Undo</button><button id="m_save">Save</button><button id="m_done" class="dim">Done</button></div>' +
      (me ? '<div class="row"><button id="m_here" class="dim">+ my position</button></div>' : ''));
    document.getElementById('m_undo').onclick = () => { pts.pop(); measDraw(); };
    document.getElementById('m_done').onclick = () => endMeasure();
    if (me) document.getElementById('m_here').onclick = () => { pts.push(me.getLatLng()); measDraw(); };
    document.getElementById('m_save').onclick = () => {
      if (pts.length < (area ? 3 : 2)) return;
      const c = pts.map(p => [+p.lng.toFixed(7), +p.lat.toFixed(7)]);
      const props = { uid: uid(), kind: area ? 'area' : 'line', name: nextName(area ? 'A' : 'L'), note: '', time: new Date().toISOString() };
      if (area) { props.area_m2 = Math.round(ringArea(pts) * 10) / 10; props.perimeter_m = Math.round(lengthOf(pts, true) * 10) / 10; }
      else props.length_m = Math.round(lengthOf(pts) * 10) / 10;
      survey.features.push({ type: 'Feature', geometry: area ? { type: 'Polygon', coordinates: [c.concat([c[0]])] } : { type: 'LineString', coordinates: c }, properties: props });
      saveSurvey(); endMeasure(); call('toast', props.name + ' saved to the survey');
    };
  }
  function measDraw() {
    meas.layer.clearLayers();
    const pts = meas.pts;
    if (pts.length > 1) (meas.kind === 'area' && pts.length > 2 ? L.polygon(pts, { color: '#ff9b3d', weight: 3, dashArray: '6 4', fillOpacity: .15 }) : L.polyline(pts, { color: '#ff9b3d', weight: 3, dashArray: '6 4' })).addTo(meas.layer);
    pts.forEach(p => L.circleMarker(p, { radius: 5, color: '#000', weight: 1, fillColor: '#ff9b3d', fillOpacity: 1 }).addTo(meas.layer));
    measPanel();
  }
  window.gisMeasure = (kind, lat, lon) => {
    endMeasure(); map.closePopup();
    meas = { kind, pts: lat != null ? [L.latLng(lat, lon)] : [], layer: L.layerGroup().addTo(map) };
    map.getContainer().style.cursor = 'crosshair';
    measDraw(); call('measuring', true);
  };
  function endMeasure() { if (!meas) return; map.removeLayer(meas.layer); meas = null; hidePanel(); map.getContainer().style.cursor = ''; call('measuring', false); }
  window.gisEndMeasure = endMeasure;
  map.on('click', e => { if (meas) { meas.pts.push(e.latlng); measDraw(); } });

  // Long-press anywhere: its grid reference, with survey tools for that spot.
  map.on('contextmenu', e => {
    if (meas) return;
    const ll = e.latlng, g = gridRef(ll.lat, ll.lng);
    const d = me ? fmtLen(me.getLatLng().distanceTo(ll)) + ' from you<br>' : '';
    L.popup({ maxWidth: 260 }).setLatLng(ll).setContent('<div class="gp">' + (g ? '<b>' + g + '</b><br>' + fmtEN(ll.lat, ll.lng) + '<br>' : '') +
      ll.lat.toFixed(6) + ', ' + ll.lng.toFixed(6) + '<br>' + d +
      '<button onclick="gisAddPoint(\'at\',' + ll.lat + ',' + ll.lng + ')">Add a survey point here</button>' +
      '<button class="dim" onclick="gisMeasure(\'line\',' + ll.lat + ',' + ll.lng + ')">Measure from here</button>' +
      '<button class="dim" onclick="Dune.copy(\'' + (g || '') + '  ' + ll.lat.toFixed(6) + ', ' + ll.lng.toFixed(6) + '\')">Copy</button></div>').openOn(map);
  });

  // ---- Export the survey: GeoJSON (any GIS) or GeoPackage (QGIS's own format), in WGS84.
  const b64 = u8 => { let s = ''; for (let i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000)); return btoa(s); };
  const stamp = () => new Date().toISOString().slice(0, 16).replace(/[-:]/g, '').replace('T', '-');
  window.gisExport = async (fmt) => {
    if (!survey.features.length) { call('toast', 'The survey is empty'); return; }
    try {
      if (fmt === 'geojson') {
        const out = { type: 'FeatureCollection', name: 'Dune survey', features: survey.features.map(f => ({ ...f, properties: Object.fromEntries(Object.entries(f.properties).filter(([k]) => k !== 'uid')) })) };
        call('saveFile', 'Dune-survey-' + stamp() + '.geojson', 'application/geo+json', btoa(unescape(encodeURIComponent(JSON.stringify(out, null, 1)))));
      } else {
        call('saveFile', 'Dune-survey-' + stamp() + '.gpkg', 'application/geopackage+sqlite3', b64(await gpkgOf(survey)));
      }
    } catch (e) { call('toast', 'Export failed: ' + (e.message || e)); }
  };
  function gpBlob(g) {
    const parts = [];
    const u8 = n => parts.push(new Uint8Array([n])), u32 = n => { const b = new ArrayBuffer(4); new DataView(b).setUint32(0, n, true); parts.push(new Uint8Array(b)); };
    const f64 = n => { const b = new ArrayBuffer(8); new DataView(b).setFloat64(0, n, true); parts.push(new Uint8Array(b)); };
    parts.push(new Uint8Array([0x47, 0x50, 0, 1])); u32(4326);   // "GP", version 0, little-endian, no envelope, EPSG:4326
    const pt = c => { f64(c[0]); f64(c[1]); };
    u8(1);
    if (g.type === 'Point') { u32(1); pt(g.coordinates); }
    else if (g.type === 'LineString') { u32(2); u32(g.coordinates.length); g.coordinates.forEach(pt); }
    else { u32(3); u32(g.coordinates.length); g.coordinates.forEach(r => { u32(r.length); r.forEach(pt); }); }
    let n = 0; parts.forEach(p => n += p.length); const out = new Uint8Array(n); let o = 0; parts.forEach(p => { out.set(p, o); o += p.length; });
    return out;
  }
  async function gpkgOf(gj) {
    const S = await sqlite(), db = new S.Database();
    const WGS = 'GEOGCS["WGS 84",DATUM["WGS_1984",SPHEROID["WGS 84",6378137,298.257223563,AUTHORITY["EPSG","7030"]],AUTHORITY["EPSG","6326"]],PRIMEM["Greenwich",0,AUTHORITY["EPSG","8901"]],UNIT["degree",0.0174532925199433,AUTHORITY["EPSG","9122"]],AUTHORITY["EPSG","4326"]]';
    db.run('PRAGMA application_id = 1196444487; PRAGMA user_version = 10300;');
    db.run(`CREATE TABLE gpkg_spatial_ref_sys (srs_name TEXT NOT NULL, srs_id INTEGER PRIMARY KEY, organization TEXT NOT NULL, organization_coordsys_id INTEGER NOT NULL, definition TEXT NOT NULL, description TEXT);
      CREATE TABLE gpkg_contents (table_name TEXT NOT NULL PRIMARY KEY, data_type TEXT NOT NULL, identifier TEXT UNIQUE, description TEXT DEFAULT '', last_change DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')), min_x DOUBLE, min_y DOUBLE, max_x DOUBLE, max_y DOUBLE, srs_id INTEGER, CONSTRAINT fk_gc_r_srs_id FOREIGN KEY (srs_id) REFERENCES gpkg_spatial_ref_sys(srs_id));
      CREATE TABLE gpkg_geometry_columns (table_name TEXT NOT NULL, column_name TEXT NOT NULL, geometry_type_name TEXT NOT NULL, srs_id INTEGER NOT NULL, z TINYINT NOT NULL, m TINYINT NOT NULL, CONSTRAINT pk_geom_cols PRIMARY KEY (table_name, column_name));`);
    db.run("INSERT INTO gpkg_spatial_ref_sys VALUES ('Undefined cartesian SRS', -1, 'NONE', -1, 'undefined', NULL), ('Undefined geographic SRS', 0, 'NONE', 0, 'undefined', NULL), ('WGS 84 geodetic', 4326, 'EPSG', 4326, ?, 'longitude/latitude coordinates in decimal degrees on the WGS 84 spheroid')", [WGS]);
    const groups = [['survey_points', 'POINT', 'point', ['name TEXT', 'note TEXT', 'time TEXT', 'gridref TEXT', 'easting REAL', 'northing REAL', 'accuracy_m REAL', 'gps_alt_m REAL']],
                    ['survey_lines', 'LINESTRING', 'line', ['name TEXT', 'note TEXT', 'time TEXT', 'length_m REAL']],
                    ['survey_areas', 'POLYGON', 'area', ['name TEXT', 'note TEXT', 'time TEXT', 'area_m2 REAL', 'perimeter_m REAL']]];
    groups.forEach(([t, type, kind, cols]) => {
      const fs = gj.features.filter(f => f.properties.kind === kind); if (!fs.length) return;
      db.run('CREATE TABLE ' + t + ' (fid INTEGER PRIMARY KEY AUTOINCREMENT, geom ' + type + ', ' + cols.join(', ') + ')');
      const names = cols.map(c => c.split(' ')[0]);
      const st = db.prepare('INSERT INTO ' + t + ' (geom, ' + names.join(', ') + ') VALUES (?' + ', ?'.repeat(names.length) + ')');
      let x0 = 180, y0 = 90, x1 = -180, y1 = -90;
      fs.forEach(f => {
        JSON.stringify(f.geometry.coordinates).match(/-?[\d.]+(e-?\d+)?,-?[\d.]+(e-?\d+)?/g).forEach(p => { const [x, y] = p.split(',').map(Number); x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y); });
        st.run([gpBlob(f.geometry)].concat(names.map(k => f.properties[k] == null ? null : f.properties[k])));
      });
      st.free();
      db.run('INSERT INTO gpkg_contents (table_name, data_type, identifier, min_x, min_y, max_x, max_y, srs_id) VALUES (?, ?, ?, ?, ?, ?, ?, 4326)', [t, 'features', t, x0, y0, x1, y1]);
      db.run('INSERT INTO gpkg_geometry_columns VALUES (?, ?, ?, 4326, 0, 0)', [t, 'geom', type]);
    });
    const bytes = db.export(); db.close();
    return bytes;
  }
  window.__gis = { gridRef, readGpkg, gpkgOf, readCsv, readGeoJSON };   // for tests
  call('gisReady');
})();
