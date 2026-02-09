// ========================================
// Application State Management
// ========================================

const AppState = {
    files: [],
    currentConfig: {
        inversionMode: 'full',
        pageRange: 'all',
        customRange: ''
    },
    processing: false,
    currentUser: null,
    theme: 'dark',
    processedFiles: []
};

// ========================================
// API Configuration
// ========================================

const API_CONFIG = {
    baseUrl: 'http://localhost:9090/api',
    endpoints: {
        process: '/pdf/process',
        batch: '/pdf/batch',
        auth: '/auth/login',
        oauth: '/auth/google'
    }
};

// ========================================
// IndexedDB for Local Storage
// ========================================

class StorageManager {
    constructor() {
        this.dbName = 'PDFInverterDB';
        this.version = 1;
        this.db = null;
    }

    async init() {
        return new Promise((resolve, reject) => {
            const request = indexedDB.open(this.dbName, this.version);

            request.onerror = () => reject(request.error);
            request.onsuccess = () => {
                this.db = request.result;
                resolve();
            };

            request.onupgradeneeded = (event) => {
                const db = event.target.result;
                
                // Store for file history
                if (!db.objectStoreNames.contains('history')) {
                    const historyStore = db.createObjectStore('history', { 
                        keyPath: 'id', 
                        autoIncrement: true 
                    });
                    historyStore.createIndex('date', 'date', { unique: false });
                }

                // Store for user preferences
                if (!db.objectStoreNames.contains('preferences')) {
                    db.createObjectStore('preferences', { keyPath: 'key' });
                }
            };
        });
    }

    async saveHistory(data) {
        const transaction = this.db.transaction(['history'], 'readwrite');
        const store = transaction.objectStore('history');
        
        const historyEntry = {
            fileName: data.fileName,
            mode: data.mode,
            pageRange: data.pageRange,
            date: new Date().toISOString(),
            fileSize: data.fileSize
        };

        return new Promise((resolve, reject) => {
            const request = store.add(historyEntry);
            request.onsuccess = () => resolve(request.result);
            request.onerror = () => reject(request.error);
        });
    }

    async getHistory(limit = 50) {
        const transaction = this.db.transaction(['history'], 'readonly');
        const store = transaction.objectStore('history');
        const index = store.index('date');

        return new Promise((resolve, reject) => {
            const request = index.openCursor(null, 'prev');
            const results = [];
            
            request.onsuccess = (event) => {
                const cursor = event.target.result;
                if (cursor && results.length < limit) {
                    results.push(cursor.value);
                    cursor.continue();
                } else {
                    resolve(results);
                }
            };
            request.onerror = () => reject(request.error);
        });
    }

    async savePreference(key, value) {
        const transaction = this.db.transaction(['preferences'], 'readwrite');
        const store = transaction.objectStore('preferences');

        return new Promise((resolve, reject) => {
            const request = store.put({ key, value });
            request.onsuccess = () => resolve();
            request.onerror = () => reject(request.error);
        });
    }

    async getPreference(key) {
        const transaction = this.db.transaction(['preferences'], 'readonly');
        const store = transaction.objectStore('preferences');

        return new Promise((resolve, reject) => {
            const request = store.get(key);
            request.onsuccess = () => resolve(request.result?.value);
            request.onerror = () => reject(request.error);
        });
    }
}

const storage = new StorageManager();

// ========================================
// Preview Controller
// ========================================

class PreviewController {
    constructor() {
        this.originalPDF = null;
        this.invertedPDF = null;
        this.currentPage = 1;
        this.totalPages = 1;
        this.zoomLevel = 1.0;
        
        this.elements = {
            originalCanvas: document.getElementById('originalCanvas'),
            invertedCanvas: document.getElementById('invertedCanvas'),
            zoomIn: document.getElementById('zoomIn'),
            zoomOut: document.getElementById('zoomOut'),
            zoomLevel: document.getElementById('zoomLevel'),
            prevPage: document.getElementById('prevPage'),
            nextPage: document.getElementById('nextPage'),
            pageInfo: document.getElementById('pageInfo')
        };
        
        this.bindEvents();
    }
    
    bindEvents() {
        this.elements.zoomIn.addEventListener('click', () => this.zoom(0.1));
        this.elements.zoomOut.addEventListener('click', () => this.zoom(-0.1));
        this.elements.prevPage.addEventListener('click', () => this.changePage(-1));
        this.elements.nextPage.addEventListener('click', () => this.changePage(1));
    }
    
    async loadPDFs(originalFile, invertedBlob) {
        try {
            const originalArrayBuffer = await originalFile.arrayBuffer();
            const invertedArrayBuffer = await invertedBlob.arrayBuffer();
            
            this.originalPDF = await pdfjsLib.getDocument({ data: originalArrayBuffer }).promise;
            this.invertedPDF = await pdfjsLib.getDocument({ data: invertedArrayBuffer }).promise;
            
            this.totalPages = this.originalPDF.numPages;
            this.currentPage = 1;
            
            await this.renderCurrentPage();
        } catch (error) {
            console.error('Error loading PDFs:', error);
            throw error;
        }
    }
    
    async renderCurrentPage() {
        try {
            const [originalPage, invertedPage] = await Promise.all([
                this.originalPDF.getPage(this.currentPage),
                this.invertedPDF.getPage(this.currentPage)
            ]);
            
            const viewport = originalPage.getViewport({ scale: this.zoomLevel });
            
            // Set canvas dimensions
            this.elements.originalCanvas.width = viewport.width;
            this.elements.originalCanvas.height = viewport.height;
            this.elements.invertedCanvas.width = viewport.width;
            this.elements.invertedCanvas.height = viewport.height;
            
            // Render both pages
            const originalContext = this.elements.originalCanvas.getContext('2d');
            const invertedContext = this.elements.invertedCanvas.getContext('2d');
            
            await Promise.all([
                originalPage.render({ canvasContext: originalContext, viewport }).promise,
                invertedPage.render({ canvasContext: invertedContext, viewport }).promise
            ]);
            
            this.updatePageInfo();
        } catch (error) {
            console.error('Error rendering page:', error);
        }
    }
    
    zoom(delta) {
        this.zoomLevel = Math.max(0.5, Math.min(3.0, this.zoomLevel + delta));
        this.elements.zoomLevel.textContent = `${Math.round(this.zoomLevel * 100)}%`;
        this.renderCurrentPage();
    }
    
    changePage(delta) {
        const newPage = this.currentPage + delta;
        if (newPage >= 1 && newPage <= this.totalPages) {
            this.currentPage = newPage;
            this.renderCurrentPage();
        }
    }
    
    updatePageInfo() {
        this.elements.pageInfo.textContent = `Page ${this.currentPage} of ${this.totalPages}`;
        this.elements.prevPage.disabled = this.currentPage === 1;
        this.elements.nextPage.disabled = this.currentPage === this.totalPages;
    }
}

// ========================================
// UI Controller
// ========================================

class UIController {
    constructor() {
        this.elements = {
            uploadSection: document.getElementById('uploadSection'),
            fileListSection: document.getElementById('fileListSection'),
            configSection: document.getElementById('configSection'),
            processingSection: document.getElementById('processingSection'),
            previewSection: document.getElementById('previewSection'),
            resultsSection: document.getElementById('resultsSection'),
            
            uploadZone: document.getElementById('uploadZone'),
            fileInput: document.getElementById('fileInput'),
            fileList: document.getElementById('fileList'),
            
            processBtn: document.getElementById('processBtn'),
            cancelBtn: document.getElementById('cancelBtn'),
            downloadBtn: document.getElementById('downloadBtn'),
            processAnotherBtn: document.getElementById('processAnotherBtn'),
            clearFiles: document.getElementById('clearFiles'),
            
            processingStatus: document.getElementById('processingStatus'),
            progressFill: document.getElementById('progressFill'),
            
            themeToggle: document.getElementById('themeToggle'),
            authBtn: document.getElementById('authBtn'),
            authModal: document.getElementById('authModal'),
            modalClose: document.getElementById('modalClose'),
            modalOverlay: document.getElementById('modalOverlay')
        };

        this.previewController = new PreviewController();
        this.bindEvents();
        this.initTheme();
    }

    bindEvents() {
        // Upload events
        this.elements.uploadZone.addEventListener('click', () => {
            this.elements.fileInput.click();
        });

        this.elements.fileInput.addEventListener('change', (e) => {
            this.handleFileSelect(e.target.files);
        });

        // Drag and drop
        this.elements.uploadZone.addEventListener('dragover', (e) => {
            e.preventDefault();
            this.elements.uploadZone.classList.add('drag-over');
        });

        this.elements.uploadZone.addEventListener('dragleave', () => {
            this.elements.uploadZone.classList.remove('drag-over');
        });

        this.elements.uploadZone.addEventListener('drop', (e) => {
            e.preventDefault();
            this.elements.uploadZone.classList.remove('drag-over');
            this.handleFileSelect(e.dataTransfer.files);
        });

        // Config events
        document.querySelectorAll('input[name="inversionMode"]').forEach(radio => {
            radio.addEventListener('change', (e) => {
                AppState.currentConfig.inversionMode = e.target.value;
            });
        });

        document.querySelectorAll('input[name="pageRange"]').forEach(radio => {
            radio.addEventListener('change', (e) => {
                AppState.currentConfig.pageRange = e.target.value;
                const customInput = document.getElementById('customRangeInput');
                customInput.style.display = e.target.value === 'custom' ? 'flex' : 'none';
            });
        });

        document.getElementById('customRange').addEventListener('input', (e) => {
            AppState.currentConfig.customRange = e.target.value;
        });

        // Preset buttons
        document.querySelectorAll('.preset-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                const mode = btn.dataset.mode;
                const range = btn.dataset.range;
                
                document.querySelector(`input[name="inversionMode"][value="${mode}"]`).checked = true;
                document.querySelector(`input[name="pageRange"][value="${range}"]`).checked = true;
                
                AppState.currentConfig.inversionMode = mode;
                AppState.currentConfig.pageRange = range;
            });
        });

        // Button events
        this.elements.processBtn?.addEventListener('click', () => {
            this.processFiles();
        });

        this.elements.clearFiles?.addEventListener('click', () => {
            this.clearAllFiles();
        });

        this.elements.cancelBtn?.addEventListener('click', () => {
            this.cancelProcessing();
        });

        this.elements.downloadBtn?.addEventListener('click', () => {
            this.downloadResults();
        });

        this.elements.processAnotherBtn?.addEventListener('click', () => {
            this.resetApp();
        });

        // Theme toggle
        this.elements.themeToggle?.addEventListener('click', () => {
            this.toggleTheme();
        });

        // Auth modal
        this.elements.authBtn?.addEventListener('click', () => {
            this.openAuthModal();
        });

        this.elements.modalClose?.addEventListener('click', () => {
            this.closeAuthModal();
        });

        this.elements.modalOverlay?.addEventListener('click', () => {
            this.closeAuthModal();
        });

        document.getElementById('authForm')?.addEventListener('submit', (e) => {
            e.preventDefault();
            this.handleAuth();
        });
    }

    handleFileSelect(files) {
        const pdfFiles = Array.from(files).filter(file => file.type === 'application/pdf');
        
        if (pdfFiles.length === 0) {
            alert('Please select PDF files only.');
            return;
        }

        // Check file size (50MB limit)
        const oversizedFiles = pdfFiles.filter(file => file.size > 50 * 1024 * 1024);
        if (oversizedFiles.length > 0) {
            alert(`The following files exceed 50MB limit: ${oversizedFiles.map(f => f.name).join(', ')}`);
            return;
        }

        pdfFiles.forEach(file => {
            AppState.files.push({
                file: file,
                name: file.name,
                size: this.formatFileSize(file.size),
                pageCount: null // Will be populated after loading
            });
        });

        this.updateFileList();
        this.showSection('fileListSection');
        this.showSection('configSection');
    }

    async updateFileList() {
        this.elements.fileList.innerHTML = '';

        for (const fileData of AppState.files) {
            // Get page count if not already loaded
            if (fileData.pageCount === null) {
                fileData.pageCount = await this.getPageCount(fileData.file);
            }

            const fileItem = document.createElement('div');
            fileItem.className = 'file-item';
            fileItem.innerHTML = `
                <div class="file-info">
                    <div class="file-icon">
                        <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor">
                            <path d="M7 2h7l5 5v11a2 2 0 01-2 2H7a2 2 0 01-2-2V4a2 2 0 012-2z"/>
                            <path d="M14 2v5h5" stroke="white" stroke-width="2" fill="none"/>
                        </svg>
                    </div>
                    <div class="file-details">
                        <div class="file-name">${fileData.name}</div>
                        <div class="file-meta">
                            <span>${fileData.size}</span>
                            <span>${fileData.pageCount} pages</span>
                        </div>
                    </div>
                </div>
                <div class="file-actions">
                    <button class="file-remove" data-file="${fileData.name}">
                        <svg width="20" height="20" viewBox="0 0 20 20" fill="currentColor">
                            <path fill-rule="evenodd" d="M9 2a1 1 0 00-.894.553L7.382 4H4a1 1 0 000 2v10a2 2 0 002 2h8a2 2 0 002-2V6a1 1 0 100-2h-3.382l-.724-1.447A1 1 0 0011 2H9zM7 8a1 1 0 012 0v6a1 1 0 11-2 0V8zm5-1a1 1 0 00-1 1v6a1 1 0 102 0V8a1 1 0 00-1-1z" clip-rule="evenodd"/>
                        </svg>
                    </button>
                </div>
            `;

            fileItem.querySelector('.file-remove').addEventListener('click', () => {
                this.removeFile(fileData.name);
            });

            this.elements.fileList.appendChild(fileItem);
        }
    }

    async getPageCount(file) {
        try {
            const arrayBuffer = await file.arrayBuffer();
            const pdf = await pdfjsLib.getDocument({ data: arrayBuffer }).promise;
            return pdf.numPages;
        } catch (error) {
            console.error('Error loading PDF:', error);
            return 0;
        }
    }

    removeFile(fileName) {
        AppState.files = AppState.files.filter(f => f.name !== fileName);
        
        if (AppState.files.length === 0) {
            this.hideSection('fileListSection');
            this.hideSection('configSection');
        } else {
            this.updateFileList();
        }
    }

    clearAllFiles() {
        AppState.files = [];
        this.hideSection('fileListSection');
        this.hideSection('configSection');
    }

    async processFiles() {
        if (AppState.files.length === 0) return;

        this.showSection('processingSection');
        this.hideSection('configSection');
        
        AppState.processing = true;

        try {
            // Simulate processing with progress updates
            const totalFiles = AppState.files.length;
            let processedCount = 0;

            for (const fileData of AppState.files) {
                processedCount++;
                
                // Update status
                this.elements.processingStatus.textContent = 
                    `Processing ${fileData.name} (${processedCount} of ${totalFiles})...`;
                
                // Update progress
                const progress = (processedCount / totalFiles) * 100;
                this.elements.progressFill.style.width = `${progress}%`;

                // Actual API call would go here
                await this.processFile(fileData);
                
                // Save to history
                await storage.saveHistory({
                    fileName: fileData.name,
                    mode: AppState.currentConfig.inversionMode,
                    pageRange: AppState.currentConfig.pageRange,
                    fileSize: fileData.size
                });
            }

            // Processing complete
            AppState.processing = false;
            this.hideSection('processingSection');
            
            // Show preview if we have processed files
            if (AppState.processedFiles.length > 0) {
                this.showSection('previewSection');
                await this.previewController.loadPDFs(
                    AppState.files[0].file, 
                    AppState.processedFiles[0].blob
                );
            }
            
            this.showSection('resultsSection');

        } catch (error) {
            console.error('Processing error:', error);
            alert('An error occurred during processing. Please try again.');
            this.cancelProcessing();
        }
    }

    async processFile(fileData) {
        const formData = new FormData();
        formData.append('file', fileData.file);
        formData.append('mode', AppState.currentConfig.inversionMode.toUpperCase().replace('-', '_'));
        formData.append('rangeType', AppState.currentConfig.pageRange.toUpperCase());
        
        if (AppState.currentConfig.pageRange === 'custom') {
            formData.append('customRange', AppState.currentConfig.customRange);
        }

        try {
            const response = await fetch(`${API_CONFIG.baseUrl}${API_CONFIG.endpoints.process}`, {
                method: 'POST',
                body: formData
            });

            if (!response.ok) {
                const errorText = await response.text();
                console.error('Server error:', errorText);
                throw new Error(`Processing failed: ${response.statusText}`);
            }

            const blob = await response.blob();
            const fileName = fileData.name.replace('.pdf', '_inverted.pdf');
            
            // Clear old processed files to avoid preview caching issues
            AppState.processedFiles = [];
            
            AppState.processedFiles.push({
                name: fileName,
                blob: blob,
                originalFile: fileData.file
            });
        } catch (error) {
            console.error('API error:', error);
            throw error;
        }
    }

    cancelProcessing() {
        AppState.processing = false;
        this.hideSection('processingSection');
        this.showSection('configSection');
    }

    downloadResults() {
        if (AppState.processedFiles.length === 1) {
            // Single file download
            const file = AppState.processedFiles[0];
            const url = URL.createObjectURL(file.blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = file.name;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
        } else {
            // ZIP download for multiple files
            alert('ZIP download for multiple files coming soon!');
        }
    }

    resetApp() {
        AppState.files = [];
        AppState.processedFiles = [];
        this.elements.fileInput.value = '';
        
        this.hideSection('fileListSection');
        this.hideSection('configSection');
        this.hideSection('resultsSection');
        this.showSection('uploadSection');
    }

    formatFileSize(bytes) {
        if (bytes === 0) return '0 Bytes';
        const k = 1024;
        const sizes = ['Bytes', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return Math.round(bytes / Math.pow(k, i) * 100) / 100 + ' ' + sizes[i];
    }

    showSection(sectionId) {
        const section = this.elements[sectionId];
        if (section) {
            section.style.display = 'block';
        }
    }

    hideSection(sectionId) {
        const section = this.elements[sectionId];
        if (section) {
            section.style.display = 'none';
        }
    }

    // Theme Management
    initTheme() {
        storage.init().then(() => {
            storage.getPreference('theme').then(theme => {
                if (theme) {
                    AppState.theme = theme;
                    document.body.className = theme === 'dark' ? 'dark-theme' : '';
                }
            });
        });
    }

    toggleTheme() {
        AppState.theme = AppState.theme === 'dark' ? 'light' : 'dark';
        document.body.className = AppState.theme === 'dark' ? 'dark-theme' : '';
        storage.savePreference('theme', AppState.theme);
    }

    // Auth Modal
    openAuthModal() {
        this.elements.authModal.classList.add('active');
    }

    closeAuthModal() {
        this.elements.authModal.classList.remove('active');
    }

    async handleAuth() {
        const email = document.getElementById('email').value;
        const password = document.getElementById('password').value;

        // In production, make actual API call
        /*
        const response = await fetch(`${API_CONFIG.baseUrl}${API_CONFIG.endpoints.auth}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email, password })
        });

        if (response.ok) {
            const user = await response.json();
            AppState.currentUser = user;
            this.closeAuthModal();
            this.elements.authBtn.textContent = user.name;
        }
        */

        // Demo
        alert('Authentication feature coming soon!');
        this.closeAuthModal();
    }
}

// ========================================
// Initialize PDF.js
// ========================================

pdfjsLib.GlobalWorkerOptions.workerSrc = 
    'https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.worker.min.js';

// ========================================
// Application Initialization
// ========================================

document.addEventListener('DOMContentLoaded', async () => {
    // Initialize storage
    await storage.init();
    
    // Initialize UI
    const ui = new UIController();
    
    console.log('PDF Inverter initialized');
});

// ========================================
// Service Worker for PWA (Optional)
// ========================================

if ('serviceWorker' in navigator) {
    window.addEventListener('load', () => {
        // navigator.serviceWorker.register('/sw.js')
        //     .then(reg => console.log('Service Worker registered'))
        //     .catch(err => console.log('Service Worker registration failed'));
    });
}
