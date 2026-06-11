package dev.blokz.arxiver.core.model

data class ArxivCategory(
    val code: String,
    val name: String,
    val group: String,
)

/**
 * The arXiv category taxonomy, bundled at build time (SPEC-DATA §2: no taxonomy
 * endpoint exists upstream; the list is stable). Source: arxiv.org/category_taxonomy.
 */
object ArxivTaxonomy {
    const val GROUP_CS = "Computer Science"
    const val GROUP_ECON = "Economics"
    const val GROUP_EESS = "Electrical Engineering and Systems Science"
    const val GROUP_MATH = "Mathematics"
    const val GROUP_PHYSICS = "Physics"
    const val GROUP_QBIO = "Quantitative Biology"
    const val GROUP_QFIN = "Quantitative Finance"
    const val GROUP_STAT = "Statistics"

    val categories: List<ArxivCategory> =
        buildList {
            // Computer Science
            cs("cs.AI", "Artificial Intelligence")
            cs("cs.AR", "Hardware Architecture")
            cs("cs.CC", "Computational Complexity")
            cs("cs.CE", "Computational Engineering, Finance, and Science")
            cs("cs.CG", "Computational Geometry")
            cs("cs.CL", "Computation and Language")
            cs("cs.CR", "Cryptography and Security")
            cs("cs.CV", "Computer Vision and Pattern Recognition")
            cs("cs.CY", "Computers and Society")
            cs("cs.DB", "Databases")
            cs("cs.DC", "Distributed, Parallel, and Cluster Computing")
            cs("cs.DL", "Digital Libraries")
            cs("cs.DM", "Discrete Mathematics")
            cs("cs.DS", "Data Structures and Algorithms")
            cs("cs.ET", "Emerging Technologies")
            cs("cs.FL", "Formal Languages and Automata Theory")
            cs("cs.GL", "General Literature")
            cs("cs.GR", "Graphics")
            cs("cs.GT", "Computer Science and Game Theory")
            cs("cs.HC", "Human-Computer Interaction")
            cs("cs.IR", "Information Retrieval")
            cs("cs.IT", "Information Theory")
            cs("cs.LG", "Machine Learning")
            cs("cs.LO", "Logic in Computer Science")
            cs("cs.MA", "Multiagent Systems")
            cs("cs.MM", "Multimedia")
            cs("cs.MS", "Mathematical Software")
            cs("cs.NA", "Numerical Analysis")
            cs("cs.NE", "Neural and Evolutionary Computing")
            cs("cs.NI", "Networking and Internet Architecture")
            cs("cs.OH", "Other Computer Science")
            cs("cs.OS", "Operating Systems")
            cs("cs.PF", "Performance")
            cs("cs.PL", "Programming Languages")
            cs("cs.RO", "Robotics")
            cs("cs.SC", "Symbolic Computation")
            cs("cs.SD", "Sound")
            cs("cs.SE", "Software Engineering")
            cs("cs.SI", "Social and Information Networks")
            cs("cs.SY", "Systems and Control")

            // Economics
            add(ArxivCategory("econ.EM", "Econometrics", GROUP_ECON))
            add(ArxivCategory("econ.GN", "General Economics", GROUP_ECON))
            add(ArxivCategory("econ.TH", "Theoretical Economics", GROUP_ECON))

            // Electrical Engineering and Systems Science
            add(ArxivCategory("eess.AS", "Audio and Speech Processing", GROUP_EESS))
            add(ArxivCategory("eess.IV", "Image and Video Processing", GROUP_EESS))
            add(ArxivCategory("eess.SP", "Signal Processing", GROUP_EESS))
            add(ArxivCategory("eess.SY", "Systems and Control", GROUP_EESS))

            // Mathematics
            math("math.AC", "Commutative Algebra")
            math("math.AG", "Algebraic Geometry")
            math("math.AP", "Analysis of PDEs")
            math("math.AT", "Algebraic Topology")
            math("math.CA", "Classical Analysis and ODEs")
            math("math.CO", "Combinatorics")
            math("math.CT", "Category Theory")
            math("math.CV", "Complex Variables")
            math("math.DG", "Differential Geometry")
            math("math.DS", "Dynamical Systems")
            math("math.FA", "Functional Analysis")
            math("math.GM", "General Mathematics")
            math("math.GN", "General Topology")
            math("math.GR", "Group Theory")
            math("math.GT", "Geometric Topology")
            math("math.HO", "History and Overview")
            math("math.IT", "Information Theory")
            math("math.KT", "K-Theory and Homology")
            math("math.LO", "Logic")
            math("math.MG", "Metric Geometry")
            math("math.MP", "Mathematical Physics")
            math("math.NA", "Numerical Analysis")
            math("math.NT", "Number Theory")
            math("math.OA", "Operator Algebras")
            math("math.OC", "Optimization and Control")
            math("math.PR", "Probability")
            math("math.QA", "Quantum Algebra")
            math("math.RA", "Rings and Algebras")
            math("math.RT", "Representation Theory")
            math("math.SG", "Symplectic Geometry")
            math("math.SP", "Spectral Theory")
            math("math.ST", "Statistics Theory")

            // Physics — astro-ph
            phys("astro-ph.CO", "Cosmology and Nongalactic Astrophysics")
            phys("astro-ph.EP", "Earth and Planetary Astrophysics")
            phys("astro-ph.GA", "Astrophysics of Galaxies")
            phys("astro-ph.HE", "High Energy Astrophysical Phenomena")
            phys("astro-ph.IM", "Instrumentation and Methods for Astrophysics")
            phys("astro-ph.SR", "Solar and Stellar Astrophysics")
            // Physics — cond-mat
            phys("cond-mat.dis-nn", "Disordered Systems and Neural Networks")
            phys("cond-mat.mes-hall", "Mesoscale and Nanoscale Physics")
            phys("cond-mat.mtrl-sci", "Materials Science")
            phys("cond-mat.other", "Other Condensed Matter")
            phys("cond-mat.quant-gas", "Quantum Gases")
            phys("cond-mat.soft", "Soft Condensed Matter")
            phys("cond-mat.stat-mech", "Statistical Mechanics")
            phys("cond-mat.str-el", "Strongly Correlated Electrons")
            phys("cond-mat.supr-con", "Superconductivity")
            // Physics — standalone archives
            phys("gr-qc", "General Relativity and Quantum Cosmology")
            phys("hep-ex", "High Energy Physics - Experiment")
            phys("hep-lat", "High Energy Physics - Lattice")
            phys("hep-ph", "High Energy Physics - Phenomenology")
            phys("hep-th", "High Energy Physics - Theory")
            phys("math-ph", "Mathematical Physics")
            // Physics — nlin
            phys("nlin.AO", "Adaptation and Self-Organizing Systems")
            phys("nlin.CD", "Chaotic Dynamics")
            phys("nlin.CG", "Cellular Automata and Lattice Gases")
            phys("nlin.PS", "Pattern Formation and Solitons")
            phys("nlin.SI", "Exactly Solvable and Integrable Systems")
            // Physics — nucl
            phys("nucl-ex", "Nuclear Experiment")
            phys("nucl-th", "Nuclear Theory")
            // Physics — physics.*
            phys("physics.acc-ph", "Accelerator Physics")
            phys("physics.ao-ph", "Atmospheric and Oceanic Physics")
            phys("physics.app-ph", "Applied Physics")
            phys("physics.atm-clus", "Atomic and Molecular Clusters")
            phys("physics.atom-ph", "Atomic Physics")
            phys("physics.bio-ph", "Biological Physics")
            phys("physics.chem-ph", "Chemical Physics")
            phys("physics.class-ph", "Classical Physics")
            phys("physics.comp-ph", "Computational Physics")
            phys("physics.data-an", "Data Analysis, Statistics and Probability")
            phys("physics.ed-ph", "Physics Education")
            phys("physics.flu-dyn", "Fluid Dynamics")
            phys("physics.gen-ph", "General Physics")
            phys("physics.geo-ph", "Geophysics")
            phys("physics.hist-ph", "History and Philosophy of Physics")
            phys("physics.ins-det", "Instrumentation and Detectors")
            phys("physics.med-ph", "Medical Physics")
            phys("physics.optics", "Optics")
            phys("physics.plasm-ph", "Plasma Physics")
            phys("physics.pop-ph", "Popular Physics")
            phys("physics.soc-ph", "Physics and Society")
            phys("physics.space-ph", "Space Physics")
            phys("quant-ph", "Quantum Physics")

            // Quantitative Biology
            qbio("q-bio.BM", "Biomolecules")
            qbio("q-bio.CB", "Cell Behavior")
            qbio("q-bio.GN", "Genomics")
            qbio("q-bio.MN", "Molecular Networks")
            qbio("q-bio.NC", "Neurons and Cognition")
            qbio("q-bio.OT", "Other Quantitative Biology")
            qbio("q-bio.PE", "Populations and Evolution")
            qbio("q-bio.QM", "Quantitative Methods")
            qbio("q-bio.SC", "Subcellular Processes")
            qbio("q-bio.TO", "Tissues and Organs")

            // Quantitative Finance
            qfin("q-fin.CP", "Computational Finance")
            qfin("q-fin.EC", "Economics")
            qfin("q-fin.GN", "General Finance")
            qfin("q-fin.MF", "Mathematical Finance")
            qfin("q-fin.PM", "Portfolio Management")
            qfin("q-fin.PR", "Pricing of Securities")
            qfin("q-fin.RM", "Risk Management")
            qfin("q-fin.ST", "Statistical Finance")
            qfin("q-fin.TR", "Trading and Market Microstructure")

            // Statistics
            stat("stat.AP", "Applications")
            stat("stat.CO", "Computation")
            stat("stat.ME", "Methodology")
            stat("stat.ML", "Machine Learning")
            stat("stat.OT", "Other Statistics")
            stat("stat.TH", "Statistics Theory")
        }

    val groups: List<String> = categories.map { it.group }.distinct()

    fun byCode(code: String): ArxivCategory? = categories.firstOrNull { it.code == code }

    private fun MutableList<ArxivCategory>.cs(
        code: String,
        name: String,
    ) = add(ArxivCategory(code, name, GROUP_CS))

    private fun MutableList<ArxivCategory>.math(
        code: String,
        name: String,
    ) = add(ArxivCategory(code, name, GROUP_MATH))

    private fun MutableList<ArxivCategory>.phys(
        code: String,
        name: String,
    ) = add(ArxivCategory(code, name, GROUP_PHYSICS))

    private fun MutableList<ArxivCategory>.qbio(
        code: String,
        name: String,
    ) = add(ArxivCategory(code, name, GROUP_QBIO))

    private fun MutableList<ArxivCategory>.qfin(
        code: String,
        name: String,
    ) = add(ArxivCategory(code, name, GROUP_QFIN))

    private fun MutableList<ArxivCategory>.stat(
        code: String,
        name: String,
    ) = add(ArxivCategory(code, name, GROUP_STAT))
}
