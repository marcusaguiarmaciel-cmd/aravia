import Foundation

/// The Terms of Use, the Privacy Policy and the health-data consent, as the person reads and
/// accepts them in the app. Raising `version` asks every account to accept again.
///
/// Written from the LGPD (Lei 13.709/2018: arts. 6, 7, 8, 9, 11, 18 and 33), the Marco Civil da
/// Internet (Lei 12.965/2014, art. 7), the Código de Defesa do Consumidor (Lei 8.078/1990, arts.
/// 47, 51 and 54), the ECA Digital (Lei 15.211/2025), ANPD Resolutions 2/2022 and 19/2024, and
/// App Store guideline 5.1.1(v). It describes what the app really does; a lawyer should review it
/// before the app is offered to the public.
enum Legal {
    static let version = 1
    static let effectiveDate = "25 de novembro de 2026"

    /// Who answers for the data (the controller). Document is injected at build time via Info.plist
    /// (LEGAL_CONTROLLER_DOC secret) so no personal tax ID is stored in source control.
    static let controllerName = "Marcus Vinícius Aguiar Maciel"
    static var controllerDocument: String {
        let raw = (Bundle.main.object(forInfoDictionaryKey: "AraviaControllerDoc") as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return raw.isEmpty || raw.contains("$(") ? "" : raw
    }
    static var controllerLine: String {
        controllerDocument.isEmpty ? controllerName : "\(controllerName) · \(controllerDocument)"
    }
    static var controllerInline: String {
        controllerDocument.isEmpty ? controllerName : "\(controllerName) (\(controllerDocument))"
    }
    static let contactEmail = "contato@aravia.app"

    struct Section: Identifiable {
        let title: String
        let body: String
        var id: String { title }
    }

    // MARK: health consent (art. 11, I: specific and set apart)

    static let healthConsent = """
    Autorizo o Aravia a ler do meu relógio e tratar, no meu celular, dados sobre a minha saúde e \
    atividade física (batimentos cardíacos, sono, oxigenação do sangue, estresse, passos, calorias, \
    treinos e rotas com localização), com a única finalidade de mostrar esses dados para mim, calcular \
    pontuações, gerar vídeos que eu pedir e fazer a arara crescer. Sei que posso revogar esta \
    autorização a qualquer momento, apagando os dados ou excluindo a conta, e que sem ela o app não \
    funciona.
    """

    // MARK: Terms of Use

    static let terms: [Section] = [
        Section(title: "1. Aceitação", body: """
        Estes Termos de Uso regem o uso do aplicativo Aravia. Ao criar uma conta e marcar que leu e \
        aceita, você concorda com eles e com a Política de Privacidade. Se não concordar, não use o \
        app. Estes termos são um contrato de adesão (art. 54 do Código de Defesa do Consumidor) e, \
        em caso de dúvida, serão interpretados da forma mais favorável a você (art. 47 do CDC).
        """),
        Section(title: "2. O que é o Aravia", body: """
        O Aravia é um aplicativo independente que conversa diretamente, por Bluetooth, com relógios \
        e pulseiras compatíveis para mostrar seus dados de atividade, sono e treinos, reconstruir \
        rotas em mapas 3D, gerar vídeos e acompanhar a sua arara.

        O Aravia não é afiliado, patrocinado nem aprovado pela Xiaomi ou por qualquer fabricante de \
        relógios. Os nomes dos aparelhos são citados apenas para indicar compatibilidade.

        O Aravia não é um dispositivo médico e não faz diagnóstico, tratamento ou prevenção de \
        doenças. Pontuações, estimativas de distância, zonas de batimento e sugestões de treino são \
        informações de bem-estar, aproximadas, e não substituem a orientação de um profissional de \
        saúde. Procure um médico antes de começar ou intensificar exercícios e diante de qualquer \
        sintoma.
        """),
        Section(title: "3. Quem pode usar", body: """
        O Aravia é destinado a pessoas com 18 anos ou mais. Ao criar a conta você declara ter 18 \
        anos ou mais. Se soubermos que uma conta pertence a uma criança ou adolescente, ela será \
        excluída, junto com os dados ligados a ela no servidor.
        """),
        Section(title: "4. Sua conta", body: """
        Para usar o app é preciso criar uma conta com nome de usuário, e-mail e senha, e confirmar \
        o e-mail. Você se compromete a informar dados verdadeiros, a manter a senha em segredo e a \
        avisar pelo contato abaixo se suspeitar de uso indevido. A conta é pessoal e intransferível.

        Você pode excluir a sua conta a qualquer momento em Ajustes › Conta › Excluir conta, sem \
        precisar falar com ninguém.
        """),
        Section(title: "5. Uso permitido", body: """
        Você concorda em não: usar o app para fins ilegais; escolher nomes de usuário, de arara ou \
        de exibição ofensivos, discriminatórios ou que se passem por outra pessoa; tentar acessar \
        contas ou dados de terceiros; interferir no funcionamento dos servidores; ou usar o app para \
        coletar dados de outras pessoas sem autorização.
        """),
        Section(title: "6. Seu relógio", body: """
        Para se conectar, o Aravia usa a chave de autenticação do seu próprio relógio, que você \
        fornece e que fica guardada só no seu celular (Keychain). O Aravia não altera o software do \
        relógio. Atualizações feitas pelo fabricante podem mudar o funcionamento do relógio e \
        interromper a compatibilidade até que o app seja atualizado.
        """),
        Section(title: "7. Ninho compartilhado", body: """
        Se você criar ou entrar num ninho, as outras pessoas do ninho verão o seu nome de exibição, \
        os seus pontos e o nome da arara. Nenhum dado de saúde é mostrado a elas, apenas o total de \
        pontos. Você pode sair do ninho quando quiser.
        """),
        Section(title: "8. Serviços de terceiros", body: """
        Alguns recursos dependem de serviços de terceiros, que têm termos próprios: Google Firebase \
        (contas e ninho), Apple Saúde / HealthKit ou Android Health Connect (somente quando você \
        autoriza ler dados de saúde do seu aparelho), Google AdMob (somente se você escolher assistir \
        aos 2 anúncios opcionais para liberar o passe Premium de 24h, sempre em modo não \
        personalizado e sem acesso a dados de saúde), Google Drive e iCloud Drive (só quando você \
        escolhe guardar vídeos neles), OpenFreeMap e OpenStreetMap (mapas) e o Instagram ou outros \
        apps para onde você compartilhar um vídeo. O Aravia não controla esses serviços.
        """),
        Section(title: "9. Propriedade intelectual", body: """
        O nome, a marca, a arara e o visual do Aravia pertencem ao seu responsável. Parte do código \
        vem do projeto Freeband, de Mert Cobanov, usado sob a licença MIT. Os dados de mapa são © \
        colaboradores do OpenStreetMap, sob a licença ODbL. Os vídeos e conteúdos que você cria são \
        seus.
        """),
        Section(title: "10. Disponibilidade", body: """
        O Aravia é oferecido gratuitamente e pode mudar, ganhar ou perder recursos. Buscamos mantê-lo \
        funcionando, mas ele pode ficar indisponível por manutenção, falhas de terceiros ou de \
        conexão. Nada nestes termos afasta os direitos que o Código de Defesa do Consumidor garante a \
        você; cláusulas que contrariem a lei não valem (art. 51 do CDC).
        """),
        Section(title: "11. Encerramento", body: """
        Você pode parar de usar o app e excluir a conta quando quiser. A conta pode ser suspensa ou \
        excluída se estes termos forem violados de forma grave, com aviso pelo e-mail cadastrado \
        sempre que possível.
        """),
        Section(title: "12. Alterações", body: """
        Quando estes termos ou a Política de Privacidade mudarem, o app mostrará o novo texto e \
        pedirá um novo aceite antes de continuar. Mudanças na finalidade do uso dos seus dados só \
        valem com o seu novo consentimento.
        """),
        Section(title: "13. Lei e foro", body: """
        Estes termos seguem as leis do Brasil. Fica eleito o foro do domicílio do usuário para \
        resolver qualquer questão.
        """),
        Section(title: "14. Contato", body: """
        \(controllerLine)
        E-mail: \(contactEmail)
        Vigência: \(effectiveDate) (versão \(version)).
        """),
    ]

    // MARK: Privacy Policy (LGPD art. 9)

    static let privacy: [Section] = [
        Section(title: "1. Quem trata os seus dados", body: """
        O controlador dos seus dados é \(controllerInline). Para qualquer \
        assunto sobre os seus dados, fale pelo e-mail \(contactEmail), que é o canal de comunicação \
        com titulares e com a Autoridade Nacional de Proteção de Dados (ANPD).
        """),
        Section(title: "2. Princípio: a saúde fica no celular", body: """
        Os dados que o relógio mede (batimentos, sono, oxigenação, estresse, passos, calorias, treinos \
        e rotas com localização) — lidos direto por Bluetooth, pelo Apple Saúde (HealthKit), pelo \
        Health Connect ou por arquivos GPX — e os do seu perfil (idade, altura, peso) são guardados e \
        processados apenas no seu celular. Eles não são enviados aos servidores do Aravia nem a redes \
        de anúncios.
        """),
        Section(title: "3. Que dados tratamos e por quê", body: """
        • Conta: nome de usuário, e-mail, senha e um identificador. Servem para criar e proteger a \
        sua conta e confirmar o e-mail (base legal: execução do contrato, art. 7º, V, da LGPD). A \
        senha é guardada pelo Google Firebase de forma cifrada; o Aravia nunca a vê.
        • Registro dos aceites: versão e data em que você aceitou estes documentos, autorizou os \
        dados de saúde e declarou ter 18 anos ou mais. Servem para comprovar o consentimento \
        (art. 8º, § 2º, e art. 7º, VI).
        • Dados de saúde e atividade: tratados no celular para mostrar a você, calcular pontuações, \
        sugerir o treino do dia, gerar vídeos e fazer a arara crescer (base legal: consentimento \
        específico e destacado, art. 11, I).
        • Ninho: nome da arara, seu nome de exibição e seus pontos (um número), para quem participa \
        do mesmo ninho (execução do contrato, a seu pedido).
        • Vídeos: gerados no celular. Só saem dele se você compartilhar ou escolher guardá-los no seu \
        Google Drive ou iCloud Drive.
        """),
        Section(title: "4. Com quem os dados são compartilhados", body: """
        O Aravia não vende dados, não usa rastreamento comportamental e nunca compartilha dados de \
        saúde com terceiros ou anunciantes para obter vantagem econômica (art. 11, § 4º).
        • Google (Firebase): guarda a conta, os aceites e o ninho, como operador. Os servidores podem \
        ficar fora do Brasil, inclusive nos Estados Unidos. Essa transferência internacional se apoia \
        nas garantias contratuais de proteção de dados oferecidas pelo Google (art. 33 da LGPD e \
        Resolução CD/ANPD nº 19/2024).
        • Google AdMob (anúncios opcionais de recompensa): ativado somente quando você escolhe tocar \
        em "Assistir anúncio" para liberar o passe Premium de 24h. Os pedidos são feitos em modo não \
        personalizado (npa=1) e nenhum dado de saúde, sono, batimento ou localização é enviado ao SDK.
        • Google Drive ou iCloud Drive: só se você conectar e escolher guardar vídeos neles, na sua \
        própria conta.
        • OpenFreeMap: recebe os pedidos de mapa, que revelam o seu endereço IP e a região exibida.
        • Pessoas do seu ninho: veem o seu nome de exibição e os seus pontos.
        • Autoridades: apenas quando houver obrigação legal ou ordem judicial.
        O app conversa direto com o relógio (ou com o Apple Saúde / Health Connect no próprio aparelho) \
        e não envia dados de saúde à Xiaomi nem a nenhum fabricante.
        """),
        Section(title: "5. Por quanto tempo", body: """
        Os dados no celular ficam até você apagá-los (Ajustes › Apagar dados deste celular) ou \
        remover o app. A conta, os aceites e o ninho ficam enquanto a conta existir e são apagados \
        quando você a exclui, exceto o que a lei obrigar a guardar.
        """),
        Section(title: "6. Segurança", body: """
        As chaves do relógio e de acesso ficam no Keychain do iPhone. Toda comunicação com servidores \
        é cifrada (HTTPS), e as regras do banco de dados só deixam cada pessoa ler e alterar o que é \
        dela. Se ocorrer um incidente de segurança que possa causar risco ou dano relevante, você e \
        a ANPD serão avisados, como manda a LGPD.
        """),
        Section(title: "7. Seus direitos", body: """
        Pela LGPD (art. 18), você pode pedir: confirmação de que tratamos seus dados; acesso a eles; \
        correção; anonimização, bloqueio ou eliminação do que for desnecessário; portabilidade; \
        eliminação dos dados tratados com consentimento; informação sobre com quem compartilhamos; \
        informação sobre a possibilidade de não consentir e suas consequências; e revogação do \
        consentimento. Você também pode reclamar à ANPD.

        No próprio app você exporta os dados (CSV ou JSON), apaga os dados do celular e exclui a \
        conta. Para o restante, escreva para \(contactEmail); a resposta completa vem em até 15 dias.
        """),
        Section(title: "8. Revogar o consentimento", body: """
        Você pode revogar a autorização de dados de saúde a qualquer momento, apagando os dados do \
        celular ou excluindo a conta. Como o app existe para mostrar esses dados, sem a autorização \
        ele não pode funcionar.
        """),
        Section(title: "9. Crianças e adolescentes", body: """
        O Aravia é só para maiores de 18 anos e não é direcionado a crianças e adolescentes. Não \
        tratamos de propósito dados de menores; contas de menores são excluídas assim que \
        identificadas.
        """),
        Section(title: "10. Mudanças nesta política", body: """
        Mudanças serão mostradas no app com destaque, antes de valer. Se a finalidade do uso dos dados \
        mudar, pediremos o seu consentimento de novo (arts. 8º, § 6º, e 9º, § 2º, da LGPD).
        """),
        Section(title: "11. Leis aplicáveis", body: """
        Lei Geral de Proteção de Dados (Lei 13.709/2018), Marco Civil da Internet (Lei 12.965/2014), \
        Código de Defesa do Consumidor (Lei 8.078/1990) e Estatuto Digital da Criança e do \
        Adolescente (Lei 15.211/2025).
        Vigência: \(effectiveDate) (versão \(version)).
        """),
    ]
}
