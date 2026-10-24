
# 🚀 QueryPilot

### Ask Questions. Get Insights. Powered by AI.

QueryPilot is an AI-powered SQL assistant that allows users to interact with databases using natural language. It uses Groq AI to help convert everyday questions into SQL-based insights, making database analysis easier and faster.

## ✨ Features

- 🤖 AI-powered SQL assistance
- 💬 Ask questions about your data in plain English
- 🗄️ SQL database file upload
- 📊 Analyze structured data using natural language
- ⚡ Groq API integration
- 🎨 Modern and interactive user interface
- 🌙 Light and dark mode
- 🔌 Spring Boot backend integration

## 🛠️ Tech Stack

- **Frontend:** HTML, CSS, JavaScript
- **Backend:** Java, Spring Boot
- **AI:** Groq API
- **Database:** SQLite
- **Build Tool:** Maven

## 🚀 Getting Started

### Prerequisites

Make sure you have the following installed:

- Java JDK
- Apache Maven
- A valid Groq API key
- A supported SQL database file

### 1. Clone the Repository

```bash
git clone YOUR_GITHUB_REPOSITORY_URL
```

Navigate to the project folder.

### 2. Configure Groq API

Get your API key from:

https://console.groq.com/keys

Configure your API key using the supported backend configuration or the API key field in the application.

**Important:** Never upload your API key to a public GitHub repository.

### 3. Start the Backend

Open a terminal inside the backend project folder and run:

```bash
mvn spring-boot:run
```

### 4. Verify the Backend

Open this URL in your browser:

http://localhost:8080/api/health

If the backend is running correctly, it should return a healthy status response.

### 5. Launch QueryPilot

Open the frontend HTML file in your browser, connect it to the backend, and upload a supported SQL database file.

Start asking questions about your data!

## 💡 Example Questions

Try asking QueryPilot:

- How many schools are in the database?
- Show all government schools.
- Compare student enrolment between 2022 and 2023.
- Which school has the highest student enrolment?
- Calculate the percentage change in enrolment.
- Show the total number of students in each district.

## 🔐 Security

- Keep your Groq API key private.
- Never commit API keys, passwords, or secrets.
- Store API keys securely on the backend.
- Avoid uploading private or sensitive database information.

## 🔮 Future Improvements

- Interactive charts and visual reports
- Support for additional database formats
- SQL query preview and editing
- User authentication
- Cloud deployment
- Exportable analysis reports

## 🤝 Contributing

Suggestions, bug reports, and contributions are welcome!

Fork this repository, make your changes, and submit a pull request.

## 📄 License

A license can be added when the project is ready for public distribution.

---

**Built with ❤️ for smarter database analysis.**

⭐ If you like QueryPilot, consider starring the repository!
