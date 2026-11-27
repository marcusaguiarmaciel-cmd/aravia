import SwiftUI

/// Opens the app only for a signed-in account with its e-mail confirmed and the current terms
/// accepted; otherwise shows the way there.
struct AccountGate<Content: View>: View {
    @StateObject private var account = Account.shared
    @ViewBuilder let content: () -> Content

    var body: some View {
        if account.isReady {
            content()
        } else {
            AccountFlowView()
        }
    }
}

/// Geometric pattern of rows of diamonds, after the body paint and basketry of Brazil's original
/// peoples, drawn as a quiet texture behind the welcome screens.
struct Grafismo: View {
    var color: Color = Color(red: 0.91, green: 0.85, blue: 0.72)
    var opacity: Double = 0.14

    var body: some View {
        Canvas { context, size in
            let step: CGFloat = 34
            var row = 0
            var y: CGFloat = step / 2
            while y < size.height + step {
                var x: CGFloat = row.isMultiple(of: 2) ? 0 : step / 2
                while x < size.width + step {
                    var d = Path()
                    d.move(to: CGPoint(x: x, y: y - 8))
                    d.addLine(to: CGPoint(x: x + 8, y: y))
                    d.addLine(to: CGPoint(x: x, y: y + 8))
                    d.addLine(to: CGPoint(x: x - 8, y: y))
                    d.closeSubpath()
                    context.stroke(d, with: .color(color.opacity(opacity)), lineWidth: 1.5)
                    x += step
                }
                y += step
                row += 1
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// Aravia's own palette for the screens before the app: night (jenipapo), straw and annatto.
private enum Look {
    static let night = Color(red: 0x16 / 255, green: 0x20 / 255, blue: 0x2B / 255)
    static let nightDeep = Color(red: 0x0E / 255, green: 0x15 / 255, blue: 0x1C / 255)
    static let straw = Color(red: 0xE9 / 255, green: 0xD9 / 255, blue: 0xB8 / 255)
    static let urucum = Color(red: 0xE0 / 255, green: 0x5A / 255, blue: 0x3A / 255)
    static let ochre = Color(red: 0xC9 / 255, green: 0x8A / 255, blue: 0x1F / 255)
    /// Solid, so the grafismo never shows through what the person types.
    static let field = Color(red: 0x24 / 255, green: 0x30 / 255, blue: 0x3D / 255)
    static let fieldLine = Color(red: 0xE9 / 255, green: 0xD9 / 255, blue: 0xB8 / 255).opacity(0.18)
}

struct AccountFlowView: View {
    enum Step { case welcome, signUp, signIn }

    @StateObject private var account = Account.shared
    @State private var step: Step = .welcome

    private var showsPattern: Bool {
        !account.isSignedIn && step == .welcome
    }

    var body: some View {
        ZStack {
            LinearGradient(colors: [Look.night, Look.nightDeep], startPoint: .top, endPoint: .bottom).ignoresSafeArea()
            // The pattern dresses the welcome only; forms sit on the plain night sky.
            if showsPattern { Grafismo().ignoresSafeArea().transition(.opacity) }
            Group {
                if account.checking {
                    VStack(spacing: 16) {
                        ProgressView().tint(Look.straw).controlSize(.large)
                        Text(verbatim: "Entrando…").font(.custom("Barlow-Medium", size: 18))
                    }
                } else if account.isSignedIn && !account.emailVerified {
                    VerifyEmailView()
                } else if account.isSignedIn && account.needsTerms {
                    TermsUpdateView()
                } else {
                    switch step {
                    case .welcome: WelcomeView(step: $step)
                    case .signUp: SignUpView(step: $step)
                    case .signIn: SignInView(step: $step)
                    }
                }
            }
            .transition(.opacity)
        }
        .foregroundStyle(Look.straw)
        .tint(Look.urucum)
        .preferredColorScheme(.dark)
        .animation(.easeInOut(duration: 0.25), value: step)
    }
}

// MARK: welcome

private struct WelcomeView: View {
    @Binding var step: AccountFlowView.Step

    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            ZStack {
                Circle().fill(Look.ochre.opacity(0.85)).frame(width: 210, height: 210).offset(x: 24, y: 6)
                AraviaArara().frame(width: 260, height: 260)
            }
            Text(verbatim: "ARAVIA")
                .font(.custom("Barlow-SemiBold", size: 54))
                .kerning(12)
                .padding(.top, 8)
            Text(verbatim: "Seu relógio, seus caminhos,\numa arara que cresce com você.")
                .font(.custom("Barlow-Medium", size: 19))
                .multilineTextAlignment(.center)
                .opacity(0.85)
                .padding(.top, 6)
            Spacer()
            VStack(spacing: 12) {
                Button { step = .signUp } label: {
                    Text(verbatim: "Criar conta").font(.custom("Barlow-SemiBold", size: 19)).frame(maxWidth: .infinity, minHeight: 54)
                }
                .buttonStyle(.borderedProminent)
                .foregroundStyle(.white)
                Button { step = .signIn } label: {
                    Text(verbatim: "Já tenho conta").font(.custom("Barlow-SemiBold", size: 18)).frame(maxWidth: .infinity, minHeight: 50)
                }
                .foregroundStyle(Look.straw)
            }
            .padding(.horizontal, 28)
            .padding(.bottom, 24)
        }
    }
}

/// The adult arara, waving its wing now and then, for the welcome screen.
private struct AraviaArara: View {
    var body: some View {
        AraraView(stage: .adult, interactive: true)
    }
}

// MARK: sign up

private struct SignUpView: View {
    @Binding var step: AccountFlowView.Step
    @StateObject private var account = Account.shared
    @State private var username = ""
    @State private var email = ""
    @State private var password = ""
    @State private var confirm = ""
    @State private var acceptTerms = false
    @State private var healthConsent = false
    @State private var adult = false
    @State private var reading: LegalDoc?
    @State private var busy = false
    @State private var error: String?

    private var normalized: String { Account.normalize(username) }
    private var passwordOK: Bool {
        password.count >= 8 && password.contains(where: \.isLetter) && password.contains(where: \.isNumber)
    }
    private var ready: Bool {
        Account.isValidUsername(normalized) && email.contains("@") && passwordOK && password == confirm
            && acceptTerms && healthConsent && adult
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                header("Criar conta", back: { step = .welcome })
                field("Nome de usuário", text: $username, hint: "3 a 20 letras minúsculas, números, ponto ou _")
                    .textInputAutocapitalization(.never)
                if !username.isEmpty && !Account.isValidUsername(normalized) {
                    note("Use de 3 a 20 caracteres: letras sem acento, números, ponto ou _.", bad: true)
                }
                field("E-mail", text: $email, keyboard: .emailAddress)
                    .textInputAutocapitalization(.never)
                    .textContentType(.emailAddress)
                secure("Senha", text: $password, content: .newPassword)
                secure("Confirmar senha", text: $confirm, content: .newPassword)
                if !password.isEmpty && !passwordOK { note("Pelo menos 8 caracteres, com letras e números.", bad: true) }
                if !confirm.isEmpty && password != confirm { note("As senhas não são iguais.", bad: true) }

                // Consents: each one on its own, unchecked by default (LGPD arts. 8 and 11).
                check($acceptTerms) {
                    Text(verbatim: "Li e aceito os ") + link("Termos de Uso") + Text(verbatim: " e a ") + link("Política de Privacidade") + Text(verbatim: ".")
                }
                HStack(spacing: 16) {
                    Button { reading = .terms } label: { Text(verbatim: "Ler os Termos") }
                    Button { reading = .privacy } label: { Text(verbatim: "Ler a Política") }
                }
                .font(.custom("Barlow-SemiBold", size: 15))
                .padding(.leading, 38)

                VStack(alignment: .leading, spacing: 8) {
                    Text(verbatim: "Dados de saúde").font(.custom("Barlow-SemiBold", size: 16)).foregroundStyle(Look.urucum)
                    Text(verbatim: Legal.healthConsent).font(.custom("Barlow-Regular", size: 14)).opacity(0.9)
                    check($healthConsent) { Text(verbatim: "Autorizo o uso dos meus dados de saúde como descrito acima.") }
                }
                .padding(14)
                .background(Look.field, in: RoundedRectangle(cornerRadius: 14))
                .overlay(RoundedRectangle(cornerRadius: 14).stroke(Look.urucum.opacity(0.7), lineWidth: 1.5))

                check($adult) { Text(verbatim: "Declaro que tenho 18 anos ou mais.") }

                if let error { note(error, bad: true) }
                Button {
                    Task {
                        busy = true
                        defer { busy = false }
                        do {
                            try await account.signUp(username: username, email: email, password: password,
                                                     terms: .init(termsAccepted: acceptTerms, healthConsent: healthConsent, adult: adult))
                        } catch { self.error = error.localizedDescription }
                    }
                } label: {
                    HStack {
                        if busy { ProgressView().tint(.white) }
                        Text(verbatim: "Criar conta").font(.custom("Barlow-SemiBold", size: 19))
                    }
                    .frame(maxWidth: .infinity, minHeight: 54)
                }
                .buttonStyle(.borderedProminent)
                .foregroundStyle(.white)
                .disabled(!ready || busy)
                .padding(.top, 6)
                Text(verbatim: "Vamos mandar um link para confirmar o seu e-mail.")
                    .font(.custom("Barlow-Regular", size: 14)).opacity(0.7)
                    .frame(maxWidth: .infinity)
            }
            .padding(24)
        }
        .scrollDismissesKeyboard(.interactively)
        .sheet(item: $reading) { LegalDocView(doc: $0) }
    }

    private func link(_ s: String) -> Text { Text(verbatim: s).foregroundColor(Look.urucum).bold() }
}

// MARK: sign in

private struct SignInView: View {
    @Binding var step: AccountFlowView.Step
    @StateObject private var account = Account.shared
    @State private var email = ""
    @State private var password = ""
    @State private var busy = false
    @State private var error: String?
    @State private var info: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                header("Entrar", back: { step = .welcome })
                field("E-mail", text: $email, keyboard: .emailAddress)
                    .textInputAutocapitalization(.never)
                    .textContentType(.username)
                secure("Senha", text: $password, content: .password)
                if let error { note(error, bad: true) }
                if let info { note(info, bad: false) }
                Button {
                    Task {
                        busy = true
                        defer { busy = false }
                        error = nil
                        do { try await account.signIn(email: email, password: password) } catch { self.error = error.localizedDescription }
                    }
                } label: {
                    HStack {
                        if busy { ProgressView().tint(.white) }
                        Text(verbatim: "Entrar").font(.custom("Barlow-SemiBold", size: 19))
                    }
                    .frame(maxWidth: .infinity, minHeight: 54)
                }
                .buttonStyle(.borderedProminent)
                .foregroundStyle(.white)
                .disabled(email.isEmpty || password.isEmpty || busy)
                Button {
                    Task {
                        error = nil
                        do {
                            try await account.sendPasswordReset(email: email)
                            info = "Se existir uma conta com esse e-mail, chegou um link para criar uma senha nova."
                        } catch { self.error = error.localizedDescription }
                    }
                } label: { Text(verbatim: "Esqueci a senha").font(.custom("Barlow-SemiBold", size: 16)) }
                .disabled(!email.contains("@"))
                .frame(maxWidth: .infinity)
            }
            .padding(24)
        }
    }
}

// MARK: confirm e-mail

private struct VerifyEmailView: View {
    @StateObject private var account = Account.shared
    @Environment(\.scenePhase) private var phase
    @State private var message: String?
    @State private var checking = false

    var body: some View {
        VStack(spacing: 18) {
            Spacer()
            Image(systemName: "envelope.badge")
                .font(.system(size: 64, weight: .semibold))
                .foregroundStyle(Look.urucum)
            Text(verbatim: "Confirme o seu e-mail").font(.custom("Barlow-SemiBold", size: 30))
            Text(verbatim: "Mandamos um link para \(account.email ?? "o seu e-mail"). Abra o e-mail, toque no link e volte aqui. Se não achar, olhe a caixa de spam.")
                .font(.custom("Barlow-Regular", size: 17))
                .multilineTextAlignment(.center)
                .opacity(0.85)
            if let message { Text(verbatim: message).font(.custom("Barlow-Medium", size: 15)).foregroundStyle(Look.ochre).multilineTextAlignment(.center) }
            Spacer()
            Button { Task { await check() } } label: {
                HStack {
                    if checking { ProgressView().tint(.white) }
                    Text(verbatim: "Já confirmei").font(.custom("Barlow-SemiBold", size: 19))
                }
                .frame(maxWidth: .infinity, minHeight: 54)
            }
            .buttonStyle(.borderedProminent)
            .foregroundStyle(.white)
            Button {
                Task {
                    do { try await account.sendVerification(); message = "E-mail enviado de novo." } catch { message = error.localizedDescription }
                }
            } label: { Text(verbatim: "Reenviar e-mail").font(.custom("Barlow-SemiBold", size: 17)) }
            Button { account.signOut() } label: { Text(verbatim: "Usar outra conta").font(.custom("Barlow-Medium", size: 16)).opacity(0.7) }
        }
        .padding(28)
        .onChange(of: phase) { _, p in if p == .active { Task { await check(quiet: true) } } }
    }

    private func check(quiet: Bool = false) async {
        checking = !quiet
        defer { checking = false }
        do {
            try await account.refreshStatus()
            if !account.emailVerified, !quiet { message = "Ainda não aparece como confirmado. Toque no link do e-mail e tente de novo." }
        } catch { if !quiet { message = error.localizedDescription } }
    }
}

// MARK: new terms

private struct TermsUpdateView: View {
    @StateObject private var account = Account.shared
    @State private var acceptTerms = false
    @State private var healthConsent = false
    @State private var adult = false
    @State private var reading: LegalDoc?
    @State private var error: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text(verbatim: account.acceptedVersion == nil ? "Antes de começar" : "Os termos mudaram")
                    .font(.custom("Barlow-SemiBold", size: 30))
                Text(verbatim: "Leia e aceite para continuar usando o Aravia.").font(.custom("Barlow-Regular", size: 17)).opacity(0.85)
                HStack(spacing: 16) {
                    Button { reading = .terms } label: { Text(verbatim: "Termos de Uso") }
                    Button { reading = .privacy } label: { Text(verbatim: "Política de Privacidade") }
                }
                .font(.custom("Barlow-SemiBold", size: 16))
                check($acceptTerms) { Text(verbatim: "Li e aceito os Termos de Uso e a Política de Privacidade.") }
                Text(verbatim: Legal.healthConsent).font(.custom("Barlow-Regular", size: 14)).opacity(0.9)
                check($healthConsent) { Text(verbatim: "Autorizo o uso dos meus dados de saúde.") }
                check($adult) { Text(verbatim: "Declaro que tenho 18 anos ou mais.") }
                if let error { note(error, bad: true) }
                Button {
                    Task {
                        do { try await account.accept(.init(termsAccepted: acceptTerms, healthConsent: healthConsent, adult: adult)) }
                        catch { self.error = error.localizedDescription }
                    }
                } label: { Text(verbatim: "Continuar").font(.custom("Barlow-SemiBold", size: 19)).frame(maxWidth: .infinity, minHeight: 54) }
                .buttonStyle(.borderedProminent)
                .foregroundStyle(.white)
                .disabled(!(acceptTerms && healthConsent && adult))
                Button { account.signOut() } label: { Text(verbatim: "Sair").frame(maxWidth: .infinity) }
            }
            .padding(24)
        }
        .sheet(item: $reading) { LegalDocView(doc: $0) }
    }
}

// MARK: legal reader

enum LegalDoc: String, Identifiable {
    case terms, privacy
    var id: String { rawValue }
    var title: String { self == .terms ? "Termos de Uso" : "Política de Privacidade" }
    var sections: [Legal.Section] { self == .terms ? Legal.terms : Legal.privacy }
}

struct LegalDocView: View {
    let doc: LegalDoc
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Text(verbatim: "Versão \(Legal.version) · em vigor desde \(Legal.effectiveDate)")
                        .font(.footnote).foregroundStyle(.secondary)
                    ForEach(doc.sections) { s in
                        VStack(alignment: .leading, spacing: 6) {
                            Text(verbatim: s.title).font(.headline)
                            Text(verbatim: s.body).font(.body).fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
                .padding(20)
                .textSelection(.enabled)
            }
            .navigationTitle(Text(verbatim: doc.title))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button { dismiss() } label: { Text(verbatim: "OK") } } }
        }
    }
}

// MARK: shared bits

private func header(_ title: String, back: @escaping () -> Void) -> some View {
    VStack(alignment: .leading, spacing: 10) {
        Button(action: back) { Label { Text(verbatim: "Voltar") } icon: { Image(systemName: "chevron.left") } }
            .font(.custom("Barlow-SemiBold", size: 16))
        Text(verbatim: title).font(.custom("Barlow-SemiBold", size: 34))
    }
    .padding(.bottom, 6)
}

private func field(_ label: String, text: Binding<String>, hint: String? = nil, keyboard: UIKeyboardType = .default) -> some View {
    VStack(alignment: .leading, spacing: 4) {
        Text(verbatim: label).font(.custom("Barlow-SemiBold", size: 15)).opacity(0.85)
        TextField(text: text) { Text(verbatim: hint ?? "").foregroundColor(Look.straw.opacity(0.35)) }
            .keyboardType(keyboard)
            .autocorrectionDisabled()
            .font(.custom("Barlow-Medium", size: 18))
            .padding(14)
            .background(Look.field, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Look.fieldLine, lineWidth: 1))
    }
}

private func secure(_ label: String, text: Binding<String>, content: UITextContentType) -> some View {
    VStack(alignment: .leading, spacing: 4) {
        Text(verbatim: label).font(.custom("Barlow-SemiBold", size: 15)).opacity(0.85)
        SecureField(text: text) { Text(verbatim: "") }
            .textContentType(content)
            .font(.custom("Barlow-Medium", size: 18))
            .padding(14)
            .background(Look.field, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Look.fieldLine, lineWidth: 1))
    }
}

private func note(_ text: String, bad: Bool) -> some View {
    Text(verbatim: text)
        .font(.custom("Barlow-Medium", size: 14))
        .foregroundStyle(bad ? Look.urucum : Look.ochre)
}

private func check<Label: View>(_ isOn: Binding<Bool>, @ViewBuilder label: () -> Label) -> some View {
    Button { isOn.wrappedValue.toggle() } label: {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: isOn.wrappedValue ? "checkmark.square.fill" : "square")
                .font(.system(size: 24))
                .foregroundStyle(isOn.wrappedValue ? Look.urucum : Look.straw.opacity(0.6))
            label()
                .font(.custom("Barlow-Medium", size: 16))
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
    }
    .buttonStyle(.plain)
    .accessibilityAddTraits(isOn.wrappedValue ? .isSelected : [])
}
