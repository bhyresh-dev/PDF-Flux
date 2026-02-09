# PDF-FLux (PDF Color Inverter)

A professional, privacy-focused web application for PDF color inversion with advanced processing modes and real-time preview.

## 🎯 Overview

This is a **production-ready** application featuring:

- **True PDF Manipulation**: Uses Apache PDFBox to parse and transform PDF content streams.
- **Privacy-First**: Local/temporary processing with auto-deletion.
- **Real-Time Preview**: Live side-by-side comparison of original vs. inverted pages.
- **Advanced Modes**:
  - **Full Inversion**: Inverts all colors (White → Black).
  - **Grayscale**: Converts to black & white, then inverts.
  - **Text-Only**: Smartly inverts dark text while preserving light backgrounds.
  - **Custom**: Optimized dark pattern for comfortable reading.

## 🚀 How to Run

You need two terminals to run the application (one for Backend, one for Frontend).

### Prerequisites
- Java 17 or higher
- Maven (required for building the backend JAR)
- Python 3 (for serving frontend)

### 1️⃣ Build Backend (First time / after cloning)
The `target/` folder is not included in the repository. You must build the JAR before running:

```powershell
mvn clean package -DskipTests
```

### 2️⃣ Start Backend (Port 9090)
This runs the Java Spring Boot server that handles PDF processing.

```powershell
# In Terminal 1
java -jar target/pdf-inverter-backend-1.0.0.jar
```

### 2️⃣ Start Frontend (Port 8080)
This serves the web interface.

```powershell
# In Terminal 2
python -m http.server 8080
```

### 3️⃣ Access Application
Open your browser and navigate to:
**http://localhost:8080/index.html**

---

## 📁 Project Structure

```
PDF-FLux/
├── src/                       # Java Spring Boot Source Code
│   └── main/java/com/pdfinverter/
│       ├── controller/        # API Endpoints
│       ├── model/             # Data Models
│       ├── service/           # PDF Processing Logic
│       └── util/              # Color Inversion Algorithms
├── target/                    # Compiled Executable (JAR files)
├── index.html                 # Frontend User Interface
├── app.js                     # Frontend Logic (API calls, Preview)
├── styles.css                 # Application Styling
├── pom.xml                    # Maven Build Configuration
└── API.md                     # API Documentation
```

## 🛠️ Development

### Rebuilding Backend
If you modify Java code, rebuild the project:

```powershell
mvn clean install -DskipTests
```

### Configuration
- **Backend Port**: 9090 (Configured in `src/main/resources/application.properties`)
- **Frontend Port**: 8080 (Default for python http.server)
- **Upload Limit**: 50MB

---

## 🏗️ Architecture

### Frontend Architecture

**Technology Stack:**
- Pure HTML5/CSS3/JavaScript (no framework lock-in)
- PDF.js for client-side PDF rendering
- IndexedDB for local data persistence
- Service Worker ready for PWA

**Key Components:**
1. **State Management**: Centralized `AppState` object
2. **UI Controller**: Manages all DOM interactions and events
3. **Storage Manager**: IndexedDB wrapper for user preferences and history
4. **API Client**: REST communication with backend

**Design Principles:**
- Mobile-first responsive design
- Accessibility (ARIA labels, keyboard navigation)
- Progressive enhancement
- Zero external dependencies for core functionality

### Backend Architecture

**Technology Stack:**
- Java 17
- Spring Boot 3.2.1
- Apache PDFBox 3.0.1 (PDF manipulation)
- Tesseract (OCR for scanned PDFs)

**Processing Pipeline (v2 – true PDF manipulation):**

```
Input PDF → Load with PDFBox → Parse Content Streams →
Detect & Invert Colour Operators (rg/RG, g/G, k/K, sc/SC, scn/SCN) →
Extract XObject Images → Invert Pixels → Re-embed →
Recurse into Form XObjects → Prepend Inverted Background →
Save Modified Document → Return to Client
```

**Core Processing Logic:**

1. **Content Stream Rewriting** (`ContentStreamColorInverter`):
   - Tokenise the page content stream via `PDFStreamParser`
   - Walk token list; when a colour operator is found, invert its operands
   - Heuristic `sc/SC/scn/SCN` handling based on operand count
   - Prepend an opaque background rectangle (inverted white)
   - Write back with FlateDecode compression

2. **Image Handling**:
   - Enumerate `PDImageXObject` entries in page resources
   - Extract to `BufferedImage`, invert pixels per mode, re-embed
   - Uses `JPEGFactory` when `compress=true`, `LosslessFactory` otherwise

3. **Form XObject Recursion**:
   - Form XObjects have their own content streams and resources
   - Recursed into automatically to invert nested graphics

4. **Metadata & Text Preservation**:
   - Original document is modified in-place (no page-to-image conversion)
   - Fonts, bookmarks, links, and annotations are untouched
   - Selectable / searchable text is fully preserved

5. **Output Controls**:
   - `outputDpi` – quality hint for image re-encoding (150 / 300 / 600)
   - `compress` – JPEG compression for embedded images (smaller output)

**Critical: This is NOT image conversion.**
We manipulate the actual PDF structure, not screenshots.

---

## 🚀 Quick Start

### Prerequisites

- **Frontend**: Any modern web browser
- **Backend**: 
  - Java 17 or higher
  - Maven 3.6+
  - 4GB RAM minimum

### Running Locally

#### 1. Start Backend

```bash
cd backend
mvn clean install
mvn spring-boot:run
```

Backend will start on `http://localhost:8080`

#### 2. Start Frontend

```bash
cd frontend
python3 -m http.server 3000
# or use any static file server
```

Frontend will be available at `http://localhost:3000`

#### 3. Test the Application

1. Open browser to `http://localhost:3000`
2. Upload a PDF file
3. Select inversion mode
4. Process and download

---

## 🔧 Configuration

### Backend Configuration

Edit `backend/src/main/resources/application.properties`:

```properties
# Server port
server.port=8080

# File upload limits
spring.servlet.multipart.max-file-size=50MB
spring.servlet.multipart.max-request-size=100MB

# PDF processing settings
pdf.processing.temp-dir=/tmp/pdf-inverter/
pdf.processing.max-pages=500
pdf.processing.default-dpi=300
```

### Frontend Configuration

Edit `frontend/app.js`:

```javascript
const API_CONFIG = {
    baseUrl: 'http://localhost:8080/api',
    endpoints: {
        process: '/pdf/process',
        batch: '/pdf/batch'
    }
};
```

---

## 📡 API Endpoints

### Process Single PDF

```http
POST /api/pdf/process
Content-Type: multipart/form-data

Parameters:
- file: PDF file (required)
- mode: FULL | GRAYSCALE | TEXT_ONLY | CUSTOM (default: FULL)
- rangeType: ALL | CUSTOM | ODD | EVEN (default: ALL)
- customRange: "1-5,10-20" (optional)
- outputDpi: 300 (optional)
- compress: false (optional)

Response: application/pdf (processed file)
```

### Process Multiple PDFs

```http
POST /api/pdf/batch
Content-Type: multipart/form-data

Parameters:
- files[]: Array of PDF files (max 20)
- mode: Inversion mode
- rangeType: Page range type

Response: application/zip (all processed files)
```

### Health Check

```http
GET /api/health

Response: 200 OK
{
  "status": "UP"
}
```

---

## 🎨 Features

### Inversion Modes

1. **Full Color Inversion**
   - Complete RGB/CMYK/Grayscale inversion
   - Best for: General use, reducing eye strain

2. **Grayscale + Invert**
   - Convert to grayscale first, then invert
   - Best for: Print-friendly documents

3. **Text-Only Inversion**
   - Inverts text and backgrounds
   - Preserves images unchanged
   - Best for: Documents with important colored diagrams

4. **Custom Dark Mode**
   - Background: #2A2A2A (dark gray)
   - Text: #E8E8E8 (off-white)
   - Best for: Maximum readability, eye comfort

### Page Range Options

- **All Pages**: Process entire document
- **Custom Range**: Specify pages (e.g., "1-5, 10-20")
- **Odd Pages**: Only odd-numbered pages
- **Even Pages**: Only even-numbered pages

### User Experience Features

- Drag & drop upload
- Real-time progress tracking
- Side-by-side preview (original vs inverted)
- Zoom and page navigation
- Dark/Light theme toggle
- Processing history (stored locally)
- Batch download as ZIP

---

## 🔒 Privacy & Security

### Data Handling

1. **No Permanent Storage**: 
   - Files processed in memory or temp directory
   - Auto-deleted after 1 hour
   - Never sent to third parties

2. **Local-First**:
   - User preferences stored in browser (IndexedDB)
   - Processing history stays on device
   - No server-side user database (in MVP)

3. **Temporary Files**:
   - Created in system temp directory
   - Unique IDs prevent collisions
   - Automatic cleanup task runs hourly

### Security Best Practices

- File type validation (PDF only)
- File size limits (50MB default)
- Input sanitization
- CORS protection
- Error messages don't leak internals

---

## 📊 Performance

### Benchmarks (on modern hardware)

- **10-page PDF**: ~2-3 seconds
- **50-page PDF**: ~8-12 seconds
- **100-page PDF**: ~20-30 seconds

### Optimization Strategies

1. **Async Processing**: Non-blocking I/O
2. **Stream Processing**: Don't load entire PDF in memory
3. **Lazy Image Loading**: Process images on-demand
4. **Compression**: Optional output compression
5. **Caching**: Reuse parsed content streams when possible

---

## 🧪 Testing

### Unit Tests

```bash
cd backend
mvn test
```

### Integration Tests

```bash
# Test API endpoint
curl -X POST http://localhost:8080/api/pdf/process \
  -F "file=@test.pdf" \
  -F "mode=FULL" \
  -F "rangeType=ALL" \
  --output inverted.pdf
```

### Frontend Tests

Open `frontend/index.html` in browser and:
1. Test file upload (drag & drop + browse)
2. Test each inversion mode
3. Test page range selection
4. Test preview functionality
5. Test theme toggle
6. Test download

---

## 🚢 Production Deployment

### Backend Deployment

#### Option 1: Docker

```dockerfile
FROM openjdk:17-slim
WORKDIR /app
COPY target/pdf-inverter-backend-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Build and run:
```bash
mvn clean package
docker build -t pdf-inverter-backend .
docker run -p 8080:8080 pdf-inverter-backend
```

#### Option 2: JAR Deployment

```bash
mvn clean package
java -jar target/pdf-inverter-backend-1.0.0.jar
```

#### Option 3: Cloud Platform (e.g., AWS)

1. Package as JAR
2. Deploy to Elastic Beanstalk or EC2
3. Configure load balancer
4. Set up auto-scaling

### Frontend Deployment

#### Static Hosting (Recommended)

1. **Netlify/Vercel**:
   - Drop `frontend/` folder
   - Auto-deploy

2. **AWS S3 + CloudFront**:
   - Upload to S3 bucket
   - Enable static website hosting
   - Add CloudFront for CDN

3. **Nginx**:
```nginx
server {
    listen 80;
    server_name pdfinverter.com;
    root /var/www/pdf-inverter/frontend;
    index index.html;
    
    location / {
        try_files $uri $uri/ =404;
    }
}
```

---

## 🔮 Scaling for Production

### Horizontal Scaling

1. **Load Balancer**: Distribute requests across backend instances
2. **Stateless Design**: No server-side sessions
3. **Shared Storage**: Use S3/MinIO for temp files
4. **Database**: Add PostgreSQL for user accounts (optional)

### Optimization Opportunities

1. **Caching**: Redis for frequently processed PDFs
2. **Queue System**: RabbitMQ/Kafka for async batch processing
3. **CDN**: CloudFront/Cloudflare for static assets
4. **Monitoring**: Prometheus + Grafana
5. **Logging**: ELK Stack (Elasticsearch, Logstash, Kibana)

### Cost Optimization

- Use serverless functions (AWS Lambda) for sporadic load
- Implement request throttling to prevent abuse
- Cache common transformations
- Compress outputs by default

---

## 🛠️ Development Workflow

### Adding a New Inversion Mode

1. **Update Model** (`PDFProcessRequest.java`):
```java
public enum InversionMode {
    FULL, GRAYSCALE, TEXT_ONLY, CUSTOM, NEW_MODE
}
```

2. **Implement Logic** (`PDFProcessingService.java`):
```java
case NEW_MODE:
    applyNewMode(document, page);
    break;
```

3. **Update Frontend** (`index.html`):
```html
<label class="mode-option">
    <input type="radio" name="inversionMode" value="new-mode">
    <span>New Mode</span>
</label>
```

### Code Quality Standards

- **Java**: Google Java Style Guide
- **JavaScript**: Airbnb JavaScript Style Guide
- **Comments**: JavaDoc for public APIs
- **Commits**: Conventional Commits format

---

## 📚 Additional Resources

### Apache PDFBox Documentation
- [Official Docs](https://pdfbox.apache.org/)
- [Content Stream Operators](https://pdfbox.apache.org/2.0/commandlineutilities.html)

### PDF Specification
- [PDF 1.7 Reference](https://www.adobe.com/content/dam/acom/en/devnet/pdf/pdfs/PDF32000_2008.pdf)

### Spring Boot Resources
- [Spring Boot Docs](https://docs.spring.io/spring-boot/docs/current/reference/html/)
- [REST API Best Practices](https://restfulapi.net/)

---

## 🤝 Contributing

This is a production application template. To contribute:

1. Fork the repository
2. Create feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit changes (`git commit -m 'Add AmazingFeature'`)
4. Push to branch (`git push origin feature/AmazingFeature`)
5. Open Pull Request

---

## 📄 License

This project is provided as a production-ready template. 
Modify and use as needed for your production environment.

---

## 💡 Support

For issues or questions:
- Check existing documentation
- Review code comments
- Test with sample PDFs
- Monitor application logs

---

**Built with ❤️ for production use**
