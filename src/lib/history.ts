const DB_NAME = "chengshu";
const STORE = "books";
const MAX_BOOKS = 24;

export type StoredBook = {
  id: string;
  title: string;
  sourceUrl: string;
  filename: string;
  format: string;
  mime: string;
  createdAt: number;
  size: number;
  charCount: number;
  paragraphCount: number;
  blob: Blob;
};

type BookRecord = Omit<StoredBook, "blob"> & { bytes: ArrayBuffer };

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 2);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(STORE)) {
        db.createObjectStore(STORE, { keyPath: "id" });
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

function guessMime(filename: string, format?: string) {
  if (format === "pdf" || filename.endsWith(".pdf")) return "application/pdf";
  if (format === "md" || filename.endsWith(".md")) return "text/markdown; charset=utf-8";
  if (format === "html" || filename.endsWith(".html")) return "text/html; charset=utf-8";
  if (format === "txt" || filename.endsWith(".txt")) return "text/plain; charset=utf-8";
  return "application/epub+zip";
}

function recordToBook(row: BookRecord): StoredBook {
  const format = row.format || (row.filename.split(".").pop() || "epub");
  const mime = row.mime || guessMime(row.filename, format);
  return {
    id: row.id,
    title: row.title,
    sourceUrl: row.sourceUrl,
    filename: row.filename,
    format,
    mime,
    createdAt: row.createdAt,
    size: row.size,
    charCount: row.charCount || 0,
    paragraphCount: row.paragraphCount || 0,
    blob: new Blob([row.bytes], { type: mime.split(";")[0] }),
  };
}

export async function saveBook(book: StoredBook): Promise<void> {
  const db = await openDb();
  const bytes = await book.blob.arrayBuffer();
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction(STORE, "readwrite");
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
    tx.objectStore(STORE).put({
      id: book.id,
      title: book.title,
      sourceUrl: book.sourceUrl,
      filename: book.filename,
      format: book.format,
      mime: book.mime,
      createdAt: book.createdAt,
      size: book.size,
      charCount: book.charCount,
      paragraphCount: book.paragraphCount,
      bytes,
    } satisfies BookRecord);
  });
  const all = await listBooks();
  if (all.length > MAX_BOOKS) {
    const extra = all.slice(MAX_BOOKS);
    await Promise.all(extra.map((item) => deleteBook(item.id)));
  }
}

export async function listBooks(): Promise<StoredBook[]> {
  const db = await openDb();
  const rows = await new Promise<BookRecord[]>((resolve, reject) => {
    const tx = db.transaction(STORE, "readonly");
    const req = tx.objectStore(STORE).getAll();
    req.onsuccess = () => resolve((req.result as BookRecord[]) ?? []);
    req.onerror = () => reject(req.error);
  });
  return rows.map(recordToBook).sort((a, b) => b.createdAt - a.createdAt);
}

export async function deleteBook(id: string): Promise<void> {
  const db = await openDb();
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction(STORE, "readwrite");
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
    tx.objectStore(STORE).delete(id);
  });
}
